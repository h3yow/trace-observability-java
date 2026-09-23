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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线程上下文栈与 MDC 联动的单元测试，重点验证线程复用不泄漏。
 */
class TraceContextHolderTest {

    @AfterEach
    void cleanUp() {
        TraceContextHolder.clear();
    }

    private static TraceContext newContext(String traceId) {
        return TraceContext.root(traceId, TraceIds.newSpanId(), false);
    }

    private static void logCase(String title, String basis, Object actual) {
        System.out.printf("[ContextHolderTest] %s | 判定依据=%s | 实际=%s%n",
                title, basis, actual);
    }

    @Test
    @DisplayName("无上下文时 current 返回 null 且 MDC 为空")
    void emptyByDefault() {
        assertNull(TraceContextHolder.current());
        assertTrue(TraceContextHolder.isEmpty());
        assertNull(MDC.get(TraceContextHolder.MDC_TRACE_ID));
        logCase("初始状态", "线程未附着任何上下文",
                "current=" + TraceContextHolder.current() + ", mdc=" + MDC.getCopyOfContextMap());
    }

    @Test
    @DisplayName("嵌套 push/pop 逐层恢复 traceId 与 spanId")
    void nestedStackRestoresLayerByLayer() {
        TraceContext root = newContext("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        TraceContext child = root.withChild(TraceIds.newSpanId());
        TraceContext grandchild = child.withChild(TraceIds.newSpanId());

        TraceContextHolder.push(root);
        assertEquals(root.traceId(), MDC.get("traceId"));
        assertEquals(root.spanId(), MDC.get("spanId"));

        TraceContextHolder.push(child);
        TraceContextHolder.push(grandchild);
        assertEquals(grandchild.spanId(), TraceContextHolder.current().spanId());
        assertEquals(root.traceId(), MDC.get("traceId"), "子 span 不改变 traceId");

        TraceContextHolder.pop();
        assertEquals(child.spanId(), TraceContextHolder.current().spanId());
        TraceContextHolder.pop();
        assertEquals(root.spanId(), TraceContextHolder.current().spanId());
        logCase("逐层弹栈", "pop 后 MDC 应恢复到上一层",
                "spanId=" + MDC.get("spanId"));

        TraceContextHolder.pop();
        assertTrue(TraceContextHolder.isEmpty());
        assertNull(MDC.get("traceId"), "栈空后 MDC 必须被移除");
    }

    @Test
    @DisplayName("Scope 异常路径也必须清理上下文")
    void scopeCleansUpOnException() {
        boolean caught = false;
        try (var ignored = TraceContextHolder.attach(newContext("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))) {
            throw new IllegalStateException("boom");
        } catch (IllegalStateException ex) {
            caught = true;
        }
        assertTrue(caught);
        assertTrue(TraceContextHolder.isEmpty());
        logCase("异常路径清理", "try-with-resources 保证 close 执行",
                "isEmpty=" + TraceContextHolder.isEmpty());
    }

    @Test
    @DisplayName("线程复用：不同任务在线程池线程上互不串扰")
    void noLeakAcrossReusedThreads() throws Exception {
        // 单线程池强制复用同一个工作线程
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            List<String> observed = new ArrayList<>();
            List<Boolean> leaksAfterTask = new ArrayList<>();

            for (int i = 0; i < 200; i++) {
                String traceId = String.format("%032d", i + 1);
                // 直接模拟"附着-清理"协议（真实传播由 ContextPropagatingExecutor 负责）
                Future<?> future = pool.submit(() -> {
                    TraceContextHolder.clear();
                    TraceContextHolder.push(newContext(traceId));
                    observed.add(TraceContextHolder.current().traceId());
                    TraceContextHolder.clear();
                    leaksAfterTask.add(TraceContextHolder.isEmpty());
                });
                future.get();
            }

            assertEquals(200, observed.size());
            for (int i = 0; i < 200; i++) {
                assertEquals(String.format("%032d", i + 1), observed.get(i),
                        "第 " + i + " 个任务读到了错误的 traceId（发生串扰）");
            }
            assertTrue(leaksAfterTask.stream().allMatch(Boolean::booleanValue),
                    "每个任务结束后线程必须无残留");
            logCase("线程复用 200 次", "同一物理线程顺序执行 200 个任务",
                    "全部读到各自 traceId 且无残留");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发：不同线程的上下文严格隔离")
    void concurrentIsolation() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                String traceId = String.format("%032d", 1000 + t);
                tasks.add(() -> {
                    TraceContextHolder.clear();
                    try (var ignored = TraceContextHolder.attach(newContext(traceId))) {
                        for (int i = 0; i < 1000; i++) {
                            TraceContext ctx = TraceContextHolder.current();
                            assertNotNull(ctx);
                            if (!traceId.equals(ctx.traceId())) {
                                return false;
                            }
                        }
                        return traceId.equals(MDC.get("traceId"));
                    } finally {
                        TraceContextHolder.clear();
                    }
                });
            }
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertTrue(result.get(), "并发期间出现上下文串扰");
            }
            logCase("32 线程 x 1000 轮", "ThreadLocal 天然按线程隔离",
                    "全部线程全程读到自身 traceId");
        } finally {
            pool.shutdownNow();
        }
    }
}
