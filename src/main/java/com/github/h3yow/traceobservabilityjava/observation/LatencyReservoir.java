package com.github.h3yow.traceobservabilityjava.observation;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 有界延迟样本池（环形缓冲）：用于在进程内计算 p50/p95/p99 等分位数。
 *
 * <p>内存与淘汰口径（明确且固定）：
 * <ul>
 *   <li>容量固定为 {@code capacity} 个 long，内存占用恒定，与请求量无关；</li>
 *   <li>写满后新样本覆盖最老样本（环形淘汰），即分位数始终反映"最近 N 次"请求；</li>
 *   <li>计数指标（count/total）不受淘汰影响，分位数只是最近窗口的视图。</li>
 * </ul>
 *
 * <p>并发：写入用 {@link AtomicLong#getAndIncrement} 取槽位 + {@link AtomicLongArray}
 * 惰性写入，多线程并发写安全；读取（分位数）先拷贝快照再排序，不阻塞写入。
 * 极端竞争下快照可能混入尚未可见的槽位（读到 0），属于"最近窗口"语义下的可接受近似，
 * 不影响计数守恒，也不会产生 NaN。
 */
public final class LatencyReservoir {

    private final AtomicLongArray ring;
    private final AtomicLong writes = new AtomicLong();
    private final int capacity;

    public LatencyReservoir(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.ring = new AtomicLongArray(capacity);
    }

    /** 记录一次耗时（纳秒，负值按 0 处理）。 */
    public void record(long durationNanos) {
        long slot = writes.getAndIncrement();
        ring.set((int) (slot % capacity), Math.max(0L, durationNanos));
    }

    /** 当前有效样本数（写满后恒等于 capacity）。 */
    public int size() {
        return (int) Math.min(writes.get(), capacity);
    }

    /** 历史累计写入次数（可能大于 capacity）。 */
    public long totalWrites() {
        return writes.get();
    }

    public int capacity() {
        return capacity;
    }

    /**
     * 计算分位数（最近窗口内样本，最近邻秩 nearest-rank 口径）。
     * 空池返回 0（绝不返回 NaN）；单样本时任何分位数都返回该样本。
     *
     * @param quantile 0~1，如 0.5 / 0.95 / 0.99
     */
    public long percentile(double quantile) {
        long[] snapshot = sortedSnapshot();
        if (snapshot.length == 0) {
            return 0L;
        }
        double q = Math.min(1.0d, Math.max(0.0d, quantile));
        int rank = (int) Math.ceil(q * snapshot.length) - 1;
        rank = Math.max(0, Math.min(snapshot.length - 1, rank));
        return snapshot[rank];
    }

    /** 当前窗口内样本的升序快照（拷贝，调用方可安全使用）。 */
    public long[] sortedSnapshot() {
        int n = size();
        long[] copy = new long[n];
        for (int i = 0; i < n; i++) {
            copy[i] = ring.get(i);
        }
        Arrays.sort(copy);
        return copy;
    }

    /** 仅测试使用：清空样本与计数。 */
    public void reset() {
        writes.set(0L);
        for (int i = 0; i < capacity; i++) {
            ring.set(i, 0L);
        }
    }
}
