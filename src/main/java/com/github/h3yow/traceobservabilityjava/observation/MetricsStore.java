package com.github.h3yow.traceobservabilityjava.observation;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程安全的进程内指标存储：按归类计数、累计耗时与延迟分位数。
 * 仅由 {@link ObservationRegistry#onSettled} 在请求最终归类落定时写入，
 * 因此每个请求对恰好一个类别贡献一次，绝不重复计数。
 *
 * <p>分位数口径：每个归类一个 {@link LatencyReservoir}，外加一个 overall 池；
 * 池满后环形淘汰最老样本，分位数反映"最近 N 次"请求；计数与累计值不受淘汰影响。
 */
public final class MetricsStore {

    /** 默认样本池容量（每归类 + overall 各一个，内存恒定）。 */
    public static final int DEFAULT_RESERVOIR_CAPACITY = 2048;

    /** 某一归类的分位数快照（不可变视图；空数据时全部为 0，绝不出现 NaN）。 */
    public record LatencyStats(long count, long totalNanos, long avgNanos,
                               long p50Nanos, long p95Nanos, long p99Nanos,
                               int sampleSize, int reservoirCapacity) {}

    private final Map<Outcome, AtomicLong> counts = new EnumMap<>(Outcome.class);
    private final Map<Outcome, AtomicLong> totalNanos = new EnumMap<>(Outcome.class);
    private final Map<Outcome, LatencyReservoir> reservoirs = new EnumMap<>(Outcome.class);
    private final LatencyReservoir overallReservoir;
    private final AtomicLong totalCount = new AtomicLong();
    private final int reservoirCapacity;

    public MetricsStore() {
        this(DEFAULT_RESERVOIR_CAPACITY);
    }

    public MetricsStore(int reservoirCapacity) {
        this.reservoirCapacity = Math.max(1, reservoirCapacity);
        this.overallReservoir = new LatencyReservoir(this.reservoirCapacity);
        for (Outcome outcome : Outcome.values()) {
            counts.put(outcome, new AtomicLong());
            totalNanos.put(outcome, new AtomicLong());
            reservoirs.put(outcome, new LatencyReservoir(this.reservoirCapacity));
        }
    }

    public void record(Outcome outcome, long durationNanos) {
        long nanos = Math.max(0L, durationNanos);
        counts.get(outcome).incrementAndGet();
        totalNanos.get(outcome).addAndGet(nanos);
        totalCount.incrementAndGet();
        reservoirs.get(outcome).record(nanos);
        overallReservoir.record(nanos);
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

    /** 成功率 = SUCCESS 数 / 总数；无数据时返回 0（不返回 NaN）。 */
    public double successRate() {
        long total = totalCount.get();
        if (total == 0) {
            return 0.0d;
        }
        return counts.get(Outcome.SUCCESS).get() / (double) total;
    }

    /** 某归类的延迟分位数快照。 */
    public LatencyStats latencyStats(Outcome outcome) {
        return statsOf(counts.get(outcome).get(), totalNanos.get(outcome).get(),
                reservoirs.get(outcome));
    }

    /** 全部请求（跨归类）的延迟分位数快照。 */
    public LatencyStats overallLatencyStats() {
        long total = totalCount.get();
        long sum = 0L;
        for (Outcome o : Outcome.values()) {
            sum += totalNanos.get(o).get();
        }
        return statsOf(total, sum, overallReservoir);
    }

    public int reservoirCapacity() {
        return reservoirCapacity;
    }

    private LatencyStats statsOf(long count, long total, LatencyReservoir reservoir) {
        long avg = count == 0 ? 0L : total / count;
        return new LatencyStats(count, total, avg,
                reservoir.percentile(0.50d), reservoir.percentile(0.95d), reservoir.percentile(0.99d),
                reservoir.size(), reservoir.capacity());
    }

    public void reset() {
        counts.values().forEach(c -> c.set(0L));
        totalNanos.values().forEach(n -> n.set(0L));
        totalCount.set(0L);
        reservoirs.values().forEach(LatencyReservoir::reset);
        overallReservoir.reset();
    }
}
