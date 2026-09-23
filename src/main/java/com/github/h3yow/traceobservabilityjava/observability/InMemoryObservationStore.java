package com.github.h3yow.traceobservabilityjava.observability;

import com.github.h3yow.traceobservabilityjava.trace.TraceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * ObservationStore 的并发安全内存实现。
 *
 * 唯一性保证：
 * <ul>
 *   <li>{@link #start} 以 traceId 幂等创建，重复入口拿到同一 Entry；</li>
 *   <li>{@link #finalize} 对 outcome 做 CAS，先到结论生效，重试/分支/异步线程
 *       的迟到裁决不会覆盖，且只有 CAS 获胜者更新指标，因此每条请求恰好计数一次；</li>
 *   <li>{@link SpanEvent} 可由任意线程追加，仅用于还原过程，从不参与归类与计数。</li>
 * </ul>
 *
 * 性能：热路径仅有 HashMap 查询、事件追加（细粒度锁）与结案时一次 CAS + 加法器，
 * 延迟样本采用环形采样（容量可配），无 IO，不显著拖慢请求。
 */
@Component
public class InMemoryObservationStore implements ObservationStore {

    private final int capacity;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final EvictingTraceOrder traceOrder;

    private final LongAdder success = new LongAdder();
    private final LongAdder businessRejected = new LongAdder();
    private final LongAdder dependencyTimeout = new LongAdder();
    private final LongAdder unexpectedError = new LongAdder();
    private final LongAdder active = new LongAdder();
    private final Map<OutcomeCategory, LatencySampler> latencyByCategory = new ConcurrentHashMap<>();

    public InMemoryObservationStore(
            @Value("${observability.store-capacity:10000}") int capacity) {
        this.capacity = Math.max(100, capacity);
        this.traceOrder = new EvictingTraceOrder(this.capacity);
        for (OutcomeCategory category : OutcomeCategory.values()) {
            latencyByCategory.put(category, new LatencySampler(2048));
        }
    }

    @Override
    public void start(String traceId, String method, String path, boolean upstreamProvided) {
        Entry entry = new Entry(traceId, method, path, upstreamProvided);
        Entry existing = entries.putIfAbsent(traceId, entry);
        if (existing == null) {
            active.increment();
            traceOrder.add(traceId);
        }
        // 同一 traceId 重复到达（理论上不应发生）时复用既有记录，绝不重复计数。
    }

    @Override
    public void event(SpanEvent event) {
        if (event == null || event.traceId() == null) {
            return;
        }
        Entry entry = entries.get(event.traceId());
        if (entry != null) {
            entry.addEvent(event);
        }
    }

    @Override
    public void event(TraceContext context, String operation, int attempt,
                      OutcomeCategory category, long elapsedNanos, Throwable error, String message) {
        if (context == null) {
            return;
        }
        SpanEvent event = new SpanEvent(
                context.traceId(), context.spanId(), context.parentSpanId(),
                operation, attempt, category, elapsedNanos,
                error == null ? null : error.getClass().getName(),
                message != null ? message : (error == null ? null : error.getMessage()),
                System.currentTimeMillis());
        Entry entry = entries.get(context.traceId());
        if (entry != null) {
            entry.addEvent(event);
        }
    }

    @Override
    public boolean finalize(String traceId, int status, OutcomeCategory category,
                            long elapsedNanos, String errorType) {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        Entry entry = entries.get(traceId);
        if (entry == null) {
            // 过滤器之外的兜底调用：补建记录再结案，保证任何请求都不会丢。
            entry = new Entry(traceId, "-", "-", false);
            Entry raced = entries.putIfAbsent(traceId, entry);
            if (raced != null) {
                entry = raced;
            } else {
                active.increment();
                traceOrder.add(traceId);
            }
        }
        FinalResult result = new FinalResult(status, category, elapsedNanos, errorType);
        if (!entry.result.compareAndSet(null, result)) {
            // 已有先到结论：拒绝覆盖、拒绝重复计数。
            return false;
        }
        active.decrement();
        switch (category) {
            case SUCCESS -> success.increment();
            case BUSINESS_REJECTED -> businessRejected.increment();
            case DEPENDENCY_TIMEOUT -> dependencyTimeout.increment();
            case UNEXPECTED_ERROR -> unexpectedError.increment();
        }
        latencyByCategory.get(category).record(elapsedNanos);
        return true;
    }

    @Override
    public RequestObservation get(String traceId) {
        Entry entry = entries.get(traceId);
        return entry == null ? null : entry.toSnapshot();
    }

    @Override
    public List<RequestObservation> recent(int limit) {
        int max = Math.max(1, Math.min(limit, capacity));
        List<String> ids = traceOrder.snapshotNewestFirst(max);
        List<RequestObservation> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            Entry entry = entries.get(id);
            if (entry != null && entry.result.get() != null) {
                result.add(entry.toSnapshot());
            }
        }
        return result;
    }

    @Override
    public MetricsSnapshot snapshot() {
        long s = success.sum();
        long b = businessRejected.sum();
        long t = dependencyTimeout.sum();
        long u = unexpectedError.sum();
        long total = s + b + t + u;
        double rate = total == 0 ? 1.0 : (double) s / total;

        // 聚合总体延迟：总量/极值直接相加各分类，分位数基于分类样本合并近似。
        List<long[]> allSamples = new ArrayList<>();
        long totalCount = 0;
        long sumNanos = 0;
        long minNanos = Long.MAX_VALUE;
        long maxNanos = Long.MIN_VALUE;
        for (LatencySampler sampler : latencyByCategory.values()) {
            synchronized (sampler) {
                totalCount += sampler.count;
                sumNanos += sampler.sumNanos;
                if (sampler.count > 0) {
                    minNanos = Math.min(minNanos, sampler.minNanos);
                    maxNanos = Math.max(maxNanos, sampler.maxNanos);
                    allSamples.add(sampler.snapshotSamples());
                }
            }
        }
        MetricsSnapshot.CategoryLatency latency = buildLatency(
                totalCount, sumNanos, minNanos, maxNanos, allSamples);
        return new MetricsSnapshot(total, s, b, t, u, active.sum(), rate, latency);
    }

    @Override
    public void reset() {
        entries.clear();
        traceOrder.clear();
        success.reset();
        businessRejected.reset();
        dependencyTimeout.reset();
        unexpectedError.reset();
        active.reset();
        for (LatencySampler sampler : latencyByCategory.values()) {
            sampler.reset();
        }
    }

    private static MetricsSnapshot.CategoryLatency buildLatency(
            long count, long sumNanos, long minNanos, long maxNanos, List<long[]> sampleParts) {
        if (count == 0) {
            return MetricsSnapshot.CategoryLatency.EMPTY;
        }
        int totalLen = sampleParts.stream().mapToInt(a -> a.length).sum();
        long[] merged = new long[totalLen];
        int offset = 0;
        for (long[] part : sampleParts) {
            System.arraycopy(part, 0, merged, offset, part.length);
            offset += part.length;
        }
        java.util.Arrays.sort(merged);
        return new MetricsSnapshot.CategoryLatency(
                count,
                (double) sumNanos / count,
                minNanos, maxNanos,
                percentile(merged, 0.50),
                percentile(merged, 0.95),
                percentile(merged, 0.99));
    }

    private static long percentile(long[] sortedAsc, double p) {
        if (sortedAsc.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p * sortedAsc.length) - 1;
        return sortedAsc[Math.max(0, Math.min(index, sortedAsc.length - 1))];
    }

    // ------------------------------------------------------------------
    // 内部类型
    // ------------------------------------------------------------------

    private record FinalResult(int status, OutcomeCategory category, long elapsedNanos,
                               String errorType) {
    }

    private static final class Entry {
        private final String traceId;
        private final String method;
        private final String path;
        private final boolean upstreamProvided;
        private final long startMillis;
        private final AtomicReference<FinalResult> result = new AtomicReference<>();
        private final List<SpanEvent> events = new ArrayList<>();

        private Entry(String traceId, String method, String path, boolean upstreamProvided) {
            this.traceId = traceId;
            this.method = method;
            this.path = path;
            this.upstreamProvided = upstreamProvided;
            this.startMillis = System.currentTimeMillis();
        }

        private void addEvent(SpanEvent event) {
            synchronized (events) {
                events.add(event);
            }
        }

        private RequestObservation toSnapshot() {
            FinalResult r = result.get();
            List<SpanEvent> snapshotEvents;
            synchronized (events) {
                snapshotEvents = List.copyOf(events);
            }
            if (r == null) {
                // 进行中：用 -1 状态与 null 归类表达"尚未定案"
                return new Snapshot(traceId, method, path, -1, null, 0L,
                        upstreamProvided, snapshotEvents, null, startMillis);
            }
            return new Snapshot(traceId, method, path, r.status(), r.category(),
                    r.elapsedNanos(), upstreamProvided, snapshotEvents, r.errorType(), startMillis);
        }
    }

    private record Snapshot(String traceId, String method, String path, int status,
                            OutcomeCategory outcome, long elapsedNanos, boolean upstreamProvided,
                            List<SpanEvent> events, String errorType,
                            long startMillis) implements RequestObservation {
    }

    /** 保持插入顺序、超出容量淘汰最旧 traceId 的有界索引。 */
    private static final class EvictingTraceOrder {
        private final ArrayDeque<String> order = new ArrayDeque<>();
        private final int capacity;

        private EvictingTraceOrder(int capacity) {
            this.capacity = capacity;
        }

        private synchronized void add(String traceId) {
            order.addLast(traceId);
            while (order.size() > capacity) {
                order.pollFirst();
            }
        }

        private synchronized List<String> snapshotNewestFirst(int limit) {
            List<String> all = new ArrayList<>(order);
            java.util.Collections.reverse(all);
            return all.size() <= limit ? all : new ArrayList<>(all.subList(0, limit));
        }

        private synchronized void clear() {
            order.clear();
        }
    }

    /** 固定容量环形采样的延迟统计：无分配增长，线程安全。 */
    private static final class LatencySampler {
        private final long[] samples;
        private int writeIndex;
        private long count;
        private long sumNanos;
        private long minNanos = Long.MAX_VALUE;
        private long maxNanos = Long.MIN_VALUE;

        private LatencySampler(int sampleCapacity) {
            this.samples = new long[sampleCapacity];
        }

        private synchronized void record(long elapsedNanos) {
            samples[writeIndex] = elapsedNanos;
            writeIndex = (writeIndex + 1) % samples.length;
            count++;
            sumNanos += elapsedNanos;
            minNanos = Math.min(minNanos, elapsedNanos);
            maxNanos = Math.max(maxNanos, elapsedNanos);
        }

        private synchronized long[] snapshotSamples() {
            int len = (int) Math.min(count, samples.length);
            long[] copy = new long[len];
            int start = count <= samples.length ? 0 : writeIndex;
            for (int i = 0; i < len; i++) {
                copy[i] = samples[(start + i) % samples.length];
            }
            return copy;
        }

        private synchronized void reset() {
            java.util.Arrays.fill(samples, 0L);
            writeIndex = 0;
            count = 0;
            sumNanos = 0;
            minNanos = Long.MAX_VALUE;
            maxNanos = Long.MIN_VALUE;
        }
    }
}
