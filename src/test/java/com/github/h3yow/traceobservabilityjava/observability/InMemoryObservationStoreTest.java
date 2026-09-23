package com.github.h3yow.traceobservabilityjava.observability;

import com.github.h3yow.traceobservabilityjava.trace.TraceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ObservationStore：唯一归类、不重复计数、事件追加与指标聚合的测试。
 */
class InMemoryObservationStoreTest {

    private InMemoryObservationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryObservationStore(1000);
    }

    private static void logCase(String title, String basis, Object actual) {
        System.out.printf("[StoreTest] %s | 判定依据=%s | 实际=%s%n", title, basis, actual);
    }

    @Test
    @DisplayName("start 幂等：同一 traceId 重复登记不增加活跃计数")
    void startIsIdempotent() {
        store.start("t1", "GET", "/a", false);
        store.start("t1", "GET", "/a", false);
        assertEquals(1, store.snapshot().activeRequests());
        assertNull(store.get("t1").outcome(), "未结案前 outcome 应为 null");
        logCase("start 幂等", "putIfAbsent 保证 Entry 唯一",
                "active=" + store.snapshot().activeRequests());
    }

    @Test
    @DisplayName("finalize 唯一：并发以不同类别结案，只有第一个生效且只计一次")
    void finalizeIsUniqueUnderConcurrency() throws Exception {
        String traceId = "t-race";
        store.start(traceId, "GET", "/x", true);

        OutcomeCategory[] candidates = {
                OutcomeCategory.SUCCESS,
                OutcomeCategory.DEPENDENCY_TIMEOUT,
                OutcomeCategory.BUSINESS_REJECTED,
                OutcomeCategory.UNEXPECTED_ERROR
        };
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int round = 0; round < 4; round++) {
                for (OutcomeCategory category : candidates) {
                    tasks.add(() -> store.finalize(traceId, 200, category, 1000, null));
                }
            }
            int wins = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (f.get()) {
                    wins++;
                }
            }
            assertEquals(1, wins, "16 次并发结案中必须恰好 1 次生效");
        } finally {
            pool.shutdownNow();
        }

        MetricsSnapshot snapshot = store.snapshot();
        long total = snapshot.totalRequests();
        assertEquals(1, total, "归类总数必须为 1，不得重复计数");
        long classified = snapshot.success() + snapshot.businessRejected()
                + snapshot.dependencyTimeout() + snapshot.unexpectedError();
        assertEquals(1, classified);
        assertEquals(0, snapshot.activeRequests(), "结案后活跃计数必须归零");
        assertNotNull(store.get(traceId).outcome());
        logCase("并发唯一结案", "16 个线程 CAS 同一 AtomicReference",
                "获胜次数=1, total=" + total + ", outcome=" + store.get(traceId).outcome());
    }

    @Test
    @DisplayName("迟到结论不得覆盖先到结论（重试/分支乱序语义）")
    void lateOutcomeCannotOverride() {
        String traceId = "t-late";
        store.start(traceId, "GET", "/y", false);
        assertTrue(store.finalize(traceId, 504, OutcomeCategory.DEPENDENCY_TIMEOUT, 5000,
                "DependencyTimeoutException"));
        // 重试分支"后来成功了"，但其结案不得改写请求结论
        assertFalse(store.finalize(traceId, 200, OutcomeCategory.SUCCESS, 6000, null));
        RequestObservation observation = store.get(traceId);
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, observation.outcome());
        assertEquals(504, observation.status());
        assertEquals(5000, observation.elapsedNanos(), "先到的耗时也不得被改写");
        assertEquals(1, store.snapshot().totalRequests());
        logCase("先到结论不可覆盖", "第二次 CAS 失败并被忽略",
                "outcome=" + observation.outcome() + ", status=" + observation.status());
    }

    @Test
    @DisplayName("中途事件可乱序、多次追加，但永远不影响最终归类与计数")
    void eventsNeverAffectOutcome() {
        String traceId = "t-events";
        store.start(traceId, "GET", "/z", false);
        TraceContext root = TraceContext.root(traceId, "1111111111111111", false);
        for (int attempt = 1; attempt <= 3; attempt++) {
            TraceContext child = root.withChild(String.format("%016d", attempt));
            store.event(child, "dependency:pay", attempt,
                    attempt == 3 ? OutcomeCategory.SUCCESS : OutcomeCategory.DEPENDENCY_TIMEOUT,
                    100L * attempt,
                    attempt == 3 ? null : new RuntimeException("timeout"),
                    attempt == 3 ? "ok" : "timeout");
        }
        store.finalize(traceId, 200, OutcomeCategory.SUCCESS, 1000, null);

        RequestObservation observation = store.get(traceId);
        assertEquals(3, observation.events().size(), "3 次尝试事件必须都保留");
        assertEquals(OutcomeCategory.SUCCESS, observation.outcome(),
                "即使前两次是超时事件，请求最终仍为 SUCCESS");
        assertEquals(1, store.snapshot().success());
        assertEquals(0, store.snapshot().dependencyTimeout(),
                "中途超时事件不进入请求级计数");
        logCase("事件与结论解耦", "2 次超时事件 + 1 次成功事件，请求只计 SUCCESS 一次",
                "events=" + observation.events().size() + ", outcome=" + observation.outcome());
    }

    @Test
    @DisplayName("高并发：5000 个不同请求的归类互不串扰，计数精确")
    void highConcurrencyNoCrossTalk() throws Exception {
        store = new InMemoryObservationStore(10_000);
        int threads = 40;
        int perThread = 125;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        String traceId = String.format("trace-%08d", base + i);
                        store.start(traceId, "GET", "/c", false);
                        OutcomeCategory category = switch ((base + i) % 4) {
                            case 0 -> OutcomeCategory.SUCCESS;
                            case 1 -> OutcomeCategory.BUSINESS_REJECTED;
                            case 2 -> OutcomeCategory.DEPENDENCY_TIMEOUT;
                            default -> OutcomeCategory.UNEXPECTED_ERROR;
                        };
                        store.finalize(traceId, 200, category, 100, null);
                    }
                    return null;
                });
            }
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get(1, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        MetricsSnapshot snapshot = store.snapshot();
        assertEquals(5000, snapshot.totalRequests());
        assertEquals(1250, snapshot.success());
        assertEquals(1250, snapshot.businessRejected());
        assertEquals(1250, snapshot.dependencyTimeout());
        assertEquals(1250, snapshot.unexpectedError());
        assertEquals(0, snapshot.activeRequests());
        // 每个 traceId 的结论可独立验证
        for (int i = 0; i < 5000; i++) {
            RequestObservation observation = store.get(String.format("trace-%08d", i));
            assertNotNull(observation);
            assertNotNull(observation.outcome());
        }
        logCase("5000 并发请求", "40 线程 x 125 请求，traceId 唯一",
                "total=" + snapshot.totalRequests() + ", active=" + snapshot.activeRequests());
    }

    @Test
    @DisplayName("reset 清空全部条目与指标")
    void resetClearsEverything() {
        store.start("a", "GET", "/a", false);
        store.finalize("a", 200, OutcomeCategory.SUCCESS, 10, null);
        store.reset();
        assertEquals(0, store.snapshot().totalRequests());
        assertNull(store.get("a"));
    }
}
