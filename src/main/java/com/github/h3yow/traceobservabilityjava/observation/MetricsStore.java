package com.github.h3yow.traceobservabilityjava.observation;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的进程内指标存储：按归类计数与累计耗时。
 * 仅由 {@link ObservationRegistry#onSettled} 在请求最终归类落定时写入，
 * 因此每个请求对恰好一个类别贡献一次，绝不重复计数。
 */
public final class MetricsStore {

    private final Map<Outcome, AtomicLong> counts = new EnumMap<>(Outcome.class);
    private final Map<Outcome, AtomicLong> totalNanos = new EnumMap<>(Outcome.class);
    private final AtomicLong totalCount = new AtomicLong();

    public MetricsStore() {
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, new AtomicLong());
            totalNanos.put(outcome, new AtomicLong());
        }
    }

    public void record(Outcome outcome, long durationNanos) {
        counts.get(outcome).incrementAndGet();
        totalNanos.get(outcome).addAndGet(Math.max(0L, durationNanos));
        totalCount.incrementAndGet();
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

    public void reset() {
        counts.values().forEach(c -> c.set(0L));
        totalNanos.values().forEach(n -> n.set(0L));
        totalCount.set(0L);
    }
}
