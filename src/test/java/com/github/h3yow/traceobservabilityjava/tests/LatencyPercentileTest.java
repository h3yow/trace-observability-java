package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.observation.LatencySamples;
import com.github.h3yow.traceobservabilityjava.observation.MetricsStore;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分位数与成功率口径：空数据不 NaN、小样本精确、并发写入精确、
 * 超容量后内存有界且结果仍在数据范围内。
 */
class LatencyPercentileTest {

    private static final Logger log = LoggerFactory.getLogger(LatencyPercentileTest.class);

    @Test
    @DisplayName("空数据：所有分位数为 0，成功率为 0.0，绝不出现 NaN")
    void emptyReturnsZeroNotNaN() {
        LatencySamples samples = new LatencySamples(128);
        MetricsStore store = new MetricsStore(128);
        log.info("[输入] 无任何样本 | [依据] 约定空数据返回 0/0.0 => p50={} p95={} p99={} "
                + "successRate={}", samples.percentile(50), samples.percentile(95),
                samples.percentile(99), store.successRate());
        assertEquals(0L, samples.percentile(50));
        assertEquals(0L, samples.percentile(95));
        assertEquals(0L, samples.percentile(99));
        assertEquals(0.0d, store.successRate());
        assertFalse(Double.isNaN(store.successRate()));
    }

    @Test
    @DisplayName("单一样本：所有分位数等于该样本")
    void singleSample() {
        LatencySamples samples = new LatencySamples(128);
        samples.record(42L);
        log.info("[输入] 单样本 42ns | [依据] 最近秩 rank=1 => p50/p95/p99 全为 42");
        assertEquals(42L, samples.percentile(50));
        assertEquals(42L, samples.percentile(95));
        assertEquals(42L, samples.percentile(99));
        assertEquals(1, samples.size());
        assertEquals(1L, samples.seen());
    }

    @Test
    @DisplayName("1..100 全量保留：最近秩分位数精确（p50=50, p95=95, p99=99）")
    void exactPercentilesBelowCapacity() {
        LatencySamples samples = new LatencySamples(1000);
        for (long i = 1; i <= 100; i++) {
            samples.record(i);
        }
        log.info("[输入] 样本 1..100（容量 1000）| [依据] 最近秩 ceil(p/100*n) => "
                + "p50={} p95={} p99={}", samples.percentile(50),
                samples.percentile(95), samples.percentile(99));
        assertEquals(50L, samples.percentile(50));
        assertEquals(95L, samples.percentile(95));
        assertEquals(99L, samples.percentile(99));
        assertEquals(100L, samples.percentile(100));
        assertEquals(1L, samples.percentile(0));
    }

    @Test
    @DisplayName("16 线程并发写 16000 个已知样本（容量内）：分位数与离线排序结果完全一致")
    void concurrentWritesExact() throws Exception {
        int threads = 16, perThread = 1000, total = threads * perThread;
        LatencySamples samples = new LatencySamples(total); // 容量 ≥ 总量 => 必须全量精确
        List<Long> expected = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long v = base + i;
                        samples.record(v);
                        expected.add(v);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        List<Long> sorted = new ArrayList<>(expected);
        Collections.sort(sorted);
        long p50 = samples.percentile(50), p95 = samples.percentile(95), p99 = samples.percentile(99);
        log.info("[输入] {} 线程 x {} 样本并发写入 | [依据] 样本数={} 应全量保留，"
                + "分位数==离线排序最近秩 => p50={} p95={} p99={}",
                threads, perThread, samples.size(), p50, p95, p99);
        assertEquals(total, samples.size(), "并发下样本一个都不能丢");
        assertEquals(total, samples.seen());
        assertEquals(sorted.get((int) Math.ceil(0.50 * total) - 1), p50);
        assertEquals(sorted.get((int) Math.ceil(0.95 * total) - 1), p95);
        assertEquals(sorted.get((int) Math.ceil(0.99 * total) - 1), p99);
    }

    @Test
    @DisplayName("超容量写入：内存恒为容量上限，seen 精确累计，分位数落在数据范围内")
    void reservoirBounded() {
        int capacity = 256;
        LatencySamples samples = new LatencySamples(capacity);
        for (long i = 1; i <= 100_000; i++) {
            samples.record(i);
        }
        long p50 = samples.percentile(50), p99 = samples.percentile(99);
        log.info("[输入] 100000 样本写入容量 {} 的蓄水池 | [依据] size=={}、seen==100000、"
                + "分位数∈[1,100000] => p50={} p99={}", capacity, samples.size(), p50, p99);
        assertEquals(capacity, samples.size(), "内存必须被容量卡住");
        assertEquals(100_000L, samples.seen(), "见过的样本总数必须精确");
        assertTrue(p50 >= 1 && p50 <= 100_000);
        assertTrue(p99 >= p50, "p99 不得小于 p50");
    }

    @Test
    @DisplayName("MetricsStore：按类别与整体样本数与计数严格对账，成功率正确")
    void storeConsistency() {
        MetricsStore store = new MetricsStore(1000);
        store.record(Outcome.SUCCESS, 100);
        store.record(Outcome.SUCCESS, 200);
        store.record(Outcome.BUSINESS_REJECTED, 300);
        store.record(Outcome.DEPENDENCY_TIMEOUT, 400);
        double rate = store.successRate();
        log.info("[输入] 2xSUCCESS + 1xREJECTED + 1xTIMEOUT | [依据] 每请求恰好一个样本 => "
                + "overall.seen==total==4，successRate=2/4=0.5，实际={}", rate);
        assertEquals(4L, store.totalCount());
        assertEquals(4L, store.overallSamples().seen());
        assertEquals(2L, store.samples(Outcome.SUCCESS).seen());
        assertEquals(1L, store.samples(Outcome.BUSINESS_REJECTED).seen());
        assertEquals(0L, store.samples(Outcome.UNEXPECTED_ERROR).seen());
        assertEquals(0.5d, rate, 1e-9);
        // SUCCESS 样本 {100,200}，最近秩 p50=ceil(0.5*2)=1 => 第 1 个 = 100
        assertEquals(100L, store.samples(Outcome.SUCCESS).percentile(50));
        assertEquals(200L, store.samples(Outcome.SUCCESS).percentile(100));
        assertEquals(400L, store.overallSamples().percentile(100));
    }
}
