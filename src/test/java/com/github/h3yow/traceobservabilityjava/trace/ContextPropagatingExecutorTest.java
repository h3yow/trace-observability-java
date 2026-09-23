package com.github.h3yow.traceobservabilityjava.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文传播线程池测试：跨线程一致、线程复用无泄漏、无上下文任务不携带旧值。
 */
class ContextPropagatingExecutorTest {

    private static final String TID = "cccccccccccccccccccccccccccccccc";

    @AfterEach
    void cleanUp() {
        TraceContextHolder.clear();
    }

    private static void logCase(String title, String basis, Object actual) {
        System.out.printf("[ExecutorTest] %s | 判定依据=%s | 实际=%s%n", title, basis, actual);
    }

    private ContextPropagatingExecutorService newPool(int size) {
        ExecutorService raw = new ThreadPoolExecutor(size, size,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "trace-test-");
                    t.setDaemon(true);
                    return t;
                });
        return new ContextPropagatingExecutorService(raw);
    }

    @Test
    @DisplayName("提交后跨线程：工作线程拿到同一 traceId，且 span 为提交点 span")
    void propagatesToWorker() throws Exception {
        ContextPropagatingExecutorService pool = newPool(2);
        try (var ignored = TraceContextHolder.attach(TraceContext.root(TID, TraceIds.newSpanId(), true))) {
            Future<String[]> future = pool.submit(() -> new String[] {
                    TraceContextHolder.current().traceId(),
                    TraceContextHolder.current().spanId(),
                    MDC.get("traceId")
            });
            String[] result = future.get(5, TimeUnit.SECONDS);
            assertEquals(TID, result[0]);
            assertEquals(TraceContextHolder.current().spanId(), result[1],
                    "传播的是提交点的当前 span");
            assertEquals(TID, result[2], "MDC 必须同步传播");
            logCase("跨线程传播", "提交时快照、执行前附着",
                    "traceId=" + result[0] + ", mdcTraceId=" + result[2]);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("线程复用不泄漏：100 个不同 traceId 的任务在 2 线程上依次执行")
    void noLeakOnReuse() throws Exception {
        ContextPropagatingExecutorService pool = newPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                String traceId = String.format("%032d", 5000 + i);
                try (var ignored = TraceContextHolder.attach(
                        TraceContext.root(traceId, TraceIds.newSpanId(), false))) {
                    futures.add(pool.submit(() -> {
                        TraceContext current = TraceContextHolder.current();
                        return current != null
                                && traceId.equals(current.traceId())
                                && traceId.equals(MDC.get("traceId"));
                    }));
                }
            }
            for (int i = 0; i < futures.size(); i++) {
                assertTrue(futures.get(i).get(5, TimeUnit.SECONDS),
                        "第 " + i + " 个任务上下文串扰");
            }
            logCase("100 任务 x 2 线程", "每任务快照不同 traceId，池线程复用",
                    "全部任务读到各自 traceId");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("无上下文任务不得让工作线程保留上一个请求的残留")
    void taskWithoutContextClearsWorker() throws Exception {
        ContextPropagatingExecutorService pool = newPool(1);
        try {
            try (var ignored = TraceContextHolder.attach(TraceContext.root(TID, TraceIds.newSpanId(), true))) {
                String first = pool.submit(() -> TraceContextHolder.current().traceId()).get(5, TimeUnit.SECONDS);
                assertEquals(TID, first);
            }
            // 主线程已无上下文，再提交任务
            TraceContextHolder.clear();
            Object second = pool.submit((Callable<Object>) () -> TraceContextHolder.current()).get(5, TimeUnit.SECONDS);
            assertNull(second, "无快照任务必须看到空上下文，而非上一个任务的 traceId");
            logCase("无上下文任务", "空快照执行前 clear，杜绝残留", "current=" + second);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("任务内再次提交到同一池：嵌套传播与结束后恢复")
    void nestedSubmissionRestores() throws Exception {
        ContextPropagatingExecutorService pool = newPool(4);
        try (var outer = TraceContextHolder.attach(TraceContext.root(TID, TraceIds.newSpanId(), true))) {
            String nestedTraceId = pool.submit(() -> {
                String inner = TraceContextHolder.current().traceId();
                // 在工作线程里再次提交
                Future<String> deeper = pool.submit(
                        () -> TraceContextHolder.current().traceId());
                String deeperTraceId;
                try {
                    deeperTraceId = deeper.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                // 内层返回后，本线程上下文仍须在
                TraceContext stillHere = TraceContextHolder.current();
                return inner + "|" + deeperTraceId + "|"
                        + (stillHere == null ? "null" : stillHere.traceId());
            }).get(5, TimeUnit.SECONDS);

            assertEquals(TID + "|" + TID + "|" + TID, nestedTraceId);
            logCase("嵌套提交", "执行前保存旧状态、结束后恢复而非清空", nestedTraceId);
            assertNotNull(TraceContextHolder.current());
        } finally {
            pool.shutdownNow();
        }
    }
}
