package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.observation.LatencyReservoir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 延迟样本池单测：空/单样本/已知分布/环形淘汰/高并发写入。
 * 每条用例打印 [输入] 与 [依据]。
 */
class LatencyReservoirTest {

    private static final Logger log = LoggerFactory.getLogger(LatencyReservoirTest.class);

    @Test
    @DisplayName("空池：所有分位数返回 0，绝不出现 NaN")
    void emptyReservoirReturnsZero() {
        LatencyReservoir r = new LatencyReservoir(128);
        log.info("[输入] 空池 | [依据] 无样本 => p50/p95/p99 必须全为 0 而非 NaN");
        assertEquals(0L, r.percentile(0.5));
        assertEquals(0L, r.percentile(0.95));
        assertEquals(0L, r.percentile(0.99));
        assertEquals(0, r.size());
    }

    @Test
    @DisplayName("单样本：任何分位数都等于该样本")
    void singleSample() {
        LatencyReservoir r = new LatencyReservoir(128);
        r.record(42L);
        log.info("[输入] 单样本 42 | [依据] n=1 => 最近邻秩恒等于唯一样本");
        assertEquals(42L, r.percentile(0.5));
        assertEquals(42L, r.percentile(0.95));
        assertEquals(42L, r.percentile(0.99));
        assertEquals(1, r.size());
    }

    @Test
    @DisplayName("已知分布 1..100：p50=50 p95=95 p99=99（最近邻秩口径）")
    void knownDistribution() {
        LatencyReservoir r = new LatencyReservoir(256);
        for (long v = 1; v <= 100; v++) {
            r.record(v);
        }
        long p50 = r.percentile(0.5), p95 = r.percentile(0.95), p99 = r.percentile(0.99);
        log.info("[输入] 样本 1..100 | [依据] nearest-rank: ceil(q*n) => p50={} p95={} p99={}",
                p50, p95, p99);
        assertEquals(50L, p50);
        assertEquals(95L, p95);
        assertEquals(99L, p99);
        assertEquals(100, r.size());
    }

    @Test
    @DisplayName("写满后环形淘汰最老样本：窗口只保留最近 N 个")
    void ringEvictsOldest() {
        LatencyReservoir r = new LatencyReservoir(10);
        for (long v = 1; v <= 20; v++) {
            r.record(v); // 最终窗口应为 11..20
        }
        long[] snapshot = r.sortedSnapshot();
        log.info("[输入] 容量 10 写入 1..20 | [依据] 环形淘汰 => 窗口=11..20，size={} writes={}",
                r.size(), r.totalWrites());
        assertEquals(10, r.size());
        assertEquals(20, r.totalWrites());
        assertEquals(11L, snapshot[0]);
        assertEquals(20L, snapshot[9]);
        assertEquals(20L, r.percentile(0.99));
    }

    @Test
    @DisplayName("高并发写入：样本数与写入次数精确一致，分位数落在值域内")
    void concurrentWritesAreConsistent() throws Exception {
        int capacity = 1024;
        int threads = 16;
        int perThread = 500; // 共 8000 次写入，远超容量，验证淘汰下仍稳定
        LatencyReservoir r = new LatencyReservoir(capacity);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        r.record(1_000_000L + i); // 1ms 起步的纳秒值
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long expectedWrites = (long) threads * perThread;
        log.info("[输入] {} 线程 x {} 次并发写入，容量 {} | [依据] writes 必须精确={}, size 必须=容量",
                threads, perThread, capacity, expectedWrites);
        assertEquals(expectedWrites, r.totalWrites(), "并发写入一次都不能丢");
        assertEquals(capacity, r.size(), "写满后窗口恒等于容量");

        long p50 = r.percentile(0.5);
        long p95 = r.percentile(0.95);
        long p99 = r.percentile(0.99);
        log.info("[依据] 分位数单调不降且落在值域 [1000000, 1000499]：p50={} p95={} p99={}",
                p50, p95, p99);
        assertTrue(p50 >= 1_000_000L && p50 <= 1_000_499L);
        assertTrue(p50 <= p95 && p95 <= p99, "分位数必须单调不降");
    }
}
