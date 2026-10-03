package com.github.h3yow.traceobservabilityjava.observation;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 有内存上限的耗时样本集（蓄水池采样，Algorithm R）。
 *
 * <p>口径说明：
 * <ul>
 *   <li>样本数 ≤ 容量时：<b>全量保留</b>，分位数为精确值；</li>
 *   <li>样本数 &gt; 容量时：以 {@code capacity/seen} 概率等概率替换，
 *       任一历史样本被保留的概率恒为 capacity/seen（无偏），内存恒为 O(capacity)；</li>
 *   <li>分位数采用<b>最近秩（nearest-rank）</b>口径：
 *       {@code rank = ceil(p/100 * n)}，取排序后第 rank 个（1 起）；</li>
 *   <li>空数据集所有分位数返回 0（绝不返回 NaN）；单样本时所有分位数等于该样本。</li>
 * </ul>
 *
 * <p>线程安全：写入用 {@link ReentrantLock} 保护（容量小、临界区为一次数组写，开销可忽略），
 * 读取对当前样本做快照排序，不与写入互斥出不一致结果。
 */
public final class LatencySamples {

    private final long[] reservoir;
    private final int capacity;
    private final AtomicLong seen = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private int size; // 当前蓄水池中有效样本数（≤ capacity），仅持锁访问

    public LatencySamples(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.reservoir = new long[this.capacity];
    }

    /** 记录一个耗时样本（纳秒，负值按 0 处理）。 */
    public void record(long nanos) {
        long value = Math.max(0L, nanos);
        long n = seen.incrementAndGet();
        if (n <= capacity) {
            lock.lock();
            try {
                reservoir[size++] = value;
            } finally {
                lock.unlock();
            }
        } else {
            // Algorithm R：以 capacity/n 概率替换蓄水池中的随机位置
            long j = ThreadLocalRandom.current().nextLong(n);
            if (j < capacity) {
                lock.lock();
                try {
                    reservoir[(int) j] = value;
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * 计算分位数（最近秩口径）。
     *
     * @param percentile 0~100（如 50/95/99），越界值会被夹到该区间
     * @return 分位数（纳秒）；无样本时返回 0
     */
    public long percentile(double percentile) {
        long[] snapshot = snapshotSorted();
        int n = snapshot.length;
        if (n == 0) {
            return 0L;
        }
        double p = Math.min(100d, Math.max(0d, percentile));
        int rank = (int) Math.ceil(p / 100d * n);
        if (rank < 1) {
            rank = 1;
        }
        return snapshot[rank - 1];
    }

    /** 当前蓄水池内样本数（≤ 容量）。 */
    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /** 历史累计见过的样本总数（含被替换淘汰的）。 */
    public long seen() {
        return seen.get();
    }

    public int capacity() {
        return capacity;
    }

    /** 仅测试使用：清空。 */
    public void reset() {
        lock.lock();
        try {
            size = 0;
            seen.set(0L);
        } finally {
            lock.unlock();
        }
    }

    private long[] snapshotSorted() {
        lock.lock();
        long[] copy;
        try {
            copy = Arrays.copyOf(reservoir, size);
        } finally {
            lock.unlock();
        }
        Arrays.sort(copy);
        return copy;
    }
}
