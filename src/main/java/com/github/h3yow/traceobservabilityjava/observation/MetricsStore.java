package com.github.h3yow.traceobservabilityjava.observation;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的进程内指标存储：
 * <ul>
 *   <li>按归类计数与累计耗时（AtomicLong，精确无上限）；</li>
 *   <li>按归类 + 整体的耗时样本集（{@link LatencySamples} 蓄水池，内存有上限），
 *       支撑 p50/p95/p99 分位数；</li>
 *   <li>成功率在读取时由计数推导：SUCCESS / total，total 为 0 时返回 0（不返回 NaN）。</li>
 * </ul>
 * 仅由 {@link ObservationRegistry#onSettled} 在请求最终归类落定时写入，
 * 因此每个请求对恰好一个类别贡献一次计数与恰好一个样本，绝不重复。
 */
public final class MetricsStore {

    private final Map<Outcome, AtomicLong> counts = new EnumMap<>(Outcome.class);
    private final Map<Outcome, AtomicLong> totalNanos = new EnumMap<>(Outcome.class);
    private final Map<Outcome, LatencySamples> samplesByOutcome = new EnumMap<>(Outcome.class);
    private final LatencySamples overallSamples;
    private final AtomicLong totalCount = new AtomicLong();

    public MetricsStore(int latencySampleCapacity) {
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, new AtomicLong());
            totalNanos.put(outcome, new AtomicLong());
            samplesByOutcome.put(outcome, new LatencySamples(latencySampleCapacity));
        }
        this.overallSamples = new LatencySamples(latencySampleCapacity);
    }

    public void record(Outcome outcome, long durationNanos) {
        counts.get(outcome).incrementAndGet();
        totalNanos.get(outcome).addAndGet(Math.max(0L, durationNanos));
        totalCount.incrementAndGet();
        samplesByOutcome.get(outcome).record(durationNanos);
        overallSamples.record(durationNanos);
    }

    public long count(Outcome outcome) {
        return counts.get(outcome).get();
    }

    public long totalNanos(Outcome outcome) {
        return totalNanos.get(outcome).get();
    }

    public long totalCount() {
        return totalCount.get();
    }

    /** 某归类的耗时样本集（有界）。 */
    public LatencySamples samples(Outcome outcome) {
        return samplesByOutcome.get(outcome);
    }

    /** 全部分类合并的耗时样本集（有界）。 */
    public LatencySamples overallSamples() {
        return overallSamples;
    }

    /**
     * 成功率 = SUCCESS 计数 / 总计数。
     * 无任何请求时返回 0.0（约定，避免 NaN）；结果恒在 [0,1]。
     */
    public double successRate() {
        long total = totalCount.get();
        if (total == 0L) {
            return 0.0d;
        }
        return counts.get(Outcome.SUCCESS).get() / (double) total;
    }

    public void reset() {
        counts.values().forEach(c -> c.set(0L));
        totalNanos.values().forEach(n -> n.set(0L));
        totalCount.set(0L);
        samplesByOutcome.values().forEach(LatencySamples::reset);
        overallSamples.reset();
    }
}
