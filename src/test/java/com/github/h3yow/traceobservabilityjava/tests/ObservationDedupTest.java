package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.observation.Observation;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 去重与"结论唯一"的核心单测，含高并发 settle：
 * 无论多少线程、以任何顺序/不同 Outcome 重复落定，最终归类只能有一个，
 * 首次成功落定次数恰为 1。
 */
class ObservationDedupTest {

    private static final Logger log = LoggerFactory.getLogger(ObservationDedupTest.class);

    @Test
    @DisplayName("同一 attemptId 重复上报只计一次")
    void attemptDedupe() {
        Observation o = new Observation("t-1");
        boolean first = o.recordAttempt("attempt-1", Outcome.DEPENDENCY_TIMEOUT, 10);
        boolean second = o.recordAttempt("attempt-1", Outcome.SUCCESS, 20);
        log.info("[输入] 同一 attempt-1 上报两次(timeout,success) | [依据] putIfAbsent 幂等 "
                + "=> first={}, second={}, attempts={}", first, second, o.attemptCount());
        assertTrue(first);
        assertFalse(second);
        assertEquals(1, o.attemptCount());
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, o.attempts().get(0).outcome(),
                "先到的尝试结论也不能被后到覆盖");
    }

    @Test
    @DisplayName("不同 attemptId（真正的多次尝试/重试）分别记录")
    void distinctAttempts() {
        Observation o = new Observation("t-2");
        o.recordAttempt("attempt-1", Outcome.DEPENDENCY_TIMEOUT, 10);
        o.recordAttempt("attempt-2", Outcome.DEPENDENCY_TIMEOUT, 10);
        o.recordAttempt("attempt-3", Outcome.SUCCESS, 10);
        log.info("[输入] attempt-1/2/3 各一次 | [依据] attemptId 不同 => attempts={}", o.attemptCount());
        assertEquals(3, o.attemptCount());
    }

    @Test
    @DisplayName("最终归类 CAS 一次性落定，后到 Outcome 不覆盖")
    void finalOutcomeWinsOnce() {
        Observation o = new Observation("t-3");
        boolean s1 = o.settleFinal(Outcome.DEPENDENCY_TIMEOUT, 100, 504);
        boolean s2 = o.settleFinal(Outcome.SUCCESS, 50, 200);
        boolean s3 = o.settleFinal(Outcome.UNEXPECTED_ERROR, 999, 500);
        log.info("[输入] 顺序 settle timeout/success/unexpected | [依据] CAS 仅首次生效 "
                + "=> settled1={} settled2={} settled3={} final={}", s1, s2, s3, o.finalOutcome());
        assertTrue(s1);
        assertFalse(s2);
        assertFalse(s3);
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, o.finalOutcome());
        assertEquals(504, o.finalHttpStatus());
    }

    @Test
    @DisplayName("高并发下多线程同时 settle，成功落定次数必须恰好为 1")
    void concurrentSettleOnlyOneWins() throws Exception {
        Observation o = new Observation("t-race");
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger accepted = new AtomicInteger();
        Outcome[] candidates = Outcome.values();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    if (o.settleFinal(candidates[idx % candidates.length], idx, 200)) {
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        log.info("[输入] {} 线程并发 settle 不同 Outcome | [依据] CAS => 成功次数={} 最终={}",
                threads, accepted.get(), o.finalOutcome());
        assertEquals(1, accepted.get(), "并发 settle 只能有一个线程首次落定");
        assertNotNull(o.finalOutcome());
    }
}
