package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.observation.Observation;
import com.github.h3yow.traceobservabilityjava.observation.ObservationContext;
import com.github.h3yow.traceobservabilityjava.observation.ObservationHolder;
import com.github.h3yow.traceobservabilityjava.trace.ContextPropagatingExecutorService;
import com.github.h3yow.traceobservabilityjava.trace.TraceContext;
import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 线程复用 / 高并发下的上下文防泄漏测试：
 * 1) 池内同一线程先后执行不同请求任务，第二个任务绝不能看到第一个请求的 traceId；
 * 2) 任务结束后工作线程上 ThreadLocal/MDC 必须被清空；
 * 3) 异步任务读到的 traceId 与提交方一致。
 */
class ThreadReuseLeakTest {

    private static final Logger log = LoggerFactory.getLogger(ThreadReuseLeakTest.class);

    private ContextPropagatingExecutorService wrapped(ThreadPoolExecutor pool) {
        return new ContextPropagatingExecutorService(pool);
    }

    @Test
    @DisplayName("同一工作线程串行执行两个请求：上下文互不串扰且线程上无残留")
    void noLeakAcrossReusedWorker() throws Exception {
        // 核心线程=最大线程=1，强制两个任务由同一个工作线程先后执行
        ThreadPoolExecutor raw = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        var pool = wrapped(raw);
        AtomicReference<String> workerName = new AtomicReference<>();

        TraceContext ctxA = new TraceContext("aaaa1111aaaa1111aaaa1111aaaa1111", "span-a", null, "req-a");
        TraceContextHolder.set(ctxA);
        ObservationHolder.set(new ObservationContext(new Observation(ctxA.traceId())));
        MDC.put("traceId", ctxA.traceId());

        CountDownLatch firstRan = new CountDownLatch(1);
        pool.execute(() -> {
            assertEquals(ctxA.traceId(), TraceContextHolder.get().traceId());
            assertEquals(ctxA.traceId(), MDC.get("traceId"));
            workerName.set(Thread.currentThread().getName());
            log.info("[请求A] worker={} 读到 traceId={} | [依据] 提交方快照已传播",
                    workerName.get(), TraceContextHolder.get().traceId());
            firstRan.countDown();
        });
        assertTrue(firstRan.await(5, TimeUnit.SECONDS));
        // 提交线程清理（模拟 Tomcat 请求结束）
        TraceContextHolder.clear();
        ObservationHolder.clear();
        MDC.clear();

        // 第二个请求：提交方设置全新上下文，同一工作线程必须读到 B，且看不到 A
        TraceContext ctxB = new TraceContext("bbbb2222bbbb2222bbbb2222bbbb2222", "span-b", null, "req-b");
        TraceContextHolder.set(ctxB);
        ObservationHolder.set(new ObservationContext(new Observation(ctxB.traceId())));
        MDC.put("traceId", ctxB.traceId());

        CountDownLatch secondRan = new CountDownLatch(1);
        pool.execute(() -> {
            String sameWorker = Thread.currentThread().getName();
            TraceContext seen = TraceContextHolder.get();
            log.info("[请求B] worker={}(与A相同={}) 读到 traceId={} | [依据] 必须是 B 且无 A 残留",
                    sameWorker, sameWorker.equals(workerName.get()), seen.traceId());
            assertEquals(sameWorker, workerName.get(), "前置条件：确实复用了同一线程");
            assertEquals(ctxB.traceId(), seen.traceId());
            assertNotEquals(ctxA.traceId(), seen.traceId());
            assertEquals(ctxB.traceId(), MDC.get("traceId"));
            secondRan.countDown();
        });
        assertTrue(secondRan.await(5, TimeUnit.SECONDS));
        TraceContextHolder.clear();
        ObservationHolder.clear();
        MDC.clear();

        // 再提交一个"空上下文"任务：此时工作线程上不应残留 B 的任何值
        CountDownLatch thirdRan = new CountDownLatch(1);
        pool.execute(() -> {
            log.info("[清理校验] worker={} tracePresent={} mdcTraceId={} | [依据] 任务结束必须清空",
                    Thread.currentThread().getName(),
                    TraceContextHolder.isPresent(), MDC.get("traceId"));
            assertNull(TraceContextHolder.get());
            assertNull(ObservationHolder.get());
            assertNull(MDC.get("traceId"));
            thirdRan.countDown();
        });
        assertTrue(thirdRan.await(5, TimeUnit.SECONDS));
        pool.shutdown();
    }

    @Test
    @DisplayName("高并发交错提交：每个任务读到的 traceId 恒等于自己提交时的快照")
    void concurrentIsolation() throws Throwable {
        ThreadPoolExecutor raw = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(256));
        var pool = wrapped(raw);
        int requests = 200;
        CountDownLatch done = new CountDownLatch(requests);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < requests; i++) {
            String traceId = String.format("%032x", (0x1000L + i));
            TraceContext ctx = new TraceContext(traceId, "span-" + i, null, "req-" + i);
            // 在提交线程设置该请求上下文后立即提交（快照在 submit 时捕获）
            TraceContextHolder.set(ctx);
            pool.execute(() -> {
                try {
                    start.await();
                    TraceContext seen = TraceContextHolder.get();
                    if (!traceId.equals(seen.traceId())) {
                        failure.compareAndSet(null, new AssertionError(
                                "串扰! 期望 " + traceId + " 实际 " + seen.traceId()));
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
            TraceContextHolder.clear();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        log.info("[输入] {} 个请求在 4 线程池交错执行 | [依据] 任务内 traceId 必须与提交快照一致",
                requests);
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
