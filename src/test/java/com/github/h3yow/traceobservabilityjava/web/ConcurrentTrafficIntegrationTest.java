package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.observability.MetricsSnapshot;
import com.github.h3yow.traceobservabilityjava.observability.ObservationStore;
import com.github.h3yow.traceobservabilityjava.observability.OutcomeCategory;
import com.github.h3yow.traceobservabilityjava.observability.RequestObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 并发混合流量集成测试：多线程同时发起四类请求，
 * 断言每条 traceId 的归类与输入预期一致，指标总数精确。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentTrafficIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObservationStore store;

    @BeforeEach
    void reset() {
        store.reset();
    }

    /** 一个请求场景：输入 traceId、路径、期望状态码与归类。 */
    private record Scenario(String traceId, String path, int expectedStatus,
                            OutcomeCategory expectedOutcome) {
    }

    @Test
    @DisplayName("24 线程 x 8 轮混合流量：结论按请求稳定，计数无重复无丢失")
    void mixedConcurrentTraffic() throws Exception {
        int threads = 24;
        int roundsPerThread = 8;
        List<Scenario> scenarios = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            for (int round = 0; round < roundsPerThread; round++) {
                int seq = t * roundsPerThread + round;
                String traceId = String.format("%032x", 0x70000000L + seq);
                scenarios.add(switch (seq % 4) {
                    case 0 -> new Scenario(traceId, "/demo/ping", 200, OutcomeCategory.SUCCESS);
                    case 1 -> new Scenario(traceId, "/demo/reject", 422,
                            OutcomeCategory.BUSINESS_REJECTED);
                    case 2 -> new Scenario(traceId,
                            "/demo/timeout?delayMillis=300&timeoutMillis=20", 504,
                            OutcomeCategory.DEPENDENCY_TIMEOUT);
                    default -> new Scenario(traceId, "/demo/boom", 500,
                            OutcomeCategory.UNEXPECTED_ERROR);
                });
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Scenario>> tasks = new ArrayList<>();
            for (Scenario scenario : scenarios) {
                tasks.add(() -> {
                    MvcResult result = mockMvc.perform(get(scenario.path())
                                    .header("X-Trace-Id", scenario.traceId()))
                            .andExpect(status().is(scenario.expectedStatus()))
                            .andReturn();
                    // 响应头必须与输入一一对应，无串扰
                    assertEquals(scenario.traceId(), result.getResponse().getHeader("X-Trace-Id"));
                    return scenario;
                });
            }
            List<Future<Scenario>> futures = pool.invokeAll(tasks);
            for (Future<Scenario> future : futures) {
                future.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        int total = threads * roundsPerThread;
        int perCategory = total / 4;
        MetricsSnapshot snapshot = store.snapshot();
        System.out.printf("[ConcurrentTraffic] 输入=%d 请求(每类%d) | 指标 total=%d success=%d "
                        + "rejected=%d timeout=%d error=%d active=%d%n",
                total, perCategory, snapshot.totalRequests(), snapshot.success(),
                snapshot.businessRejected(), snapshot.dependencyTimeout(),
                snapshot.unexpectedError(), snapshot.activeRequests());

        assertEquals(total, snapshot.totalRequests(), "总数必须精确，无重复计数或丢失");
        assertEquals(perCategory, snapshot.success());
        assertEquals(perCategory, snapshot.businessRejected());
        assertEquals(perCategory, snapshot.dependencyTimeout());
        assertEquals(perCategory, snapshot.unexpectedError());
        assertEquals(0, snapshot.activeRequests());

        // 逐条核对：每个 traceId 的最终归类与其输入场景一致，互相不串扰
        for (Scenario scenario : scenarios) {
            RequestObservation observation = store.get(scenario.traceId());
            assertNotNull(observation, "链路缺失: " + scenario.traceId());
            assertEquals(scenario.expectedOutcome(), observation.outcome(),
                    "traceId=" + scenario.traceId() + " 归类串扰");
            assertEquals(scenario.expectedStatus(), observation.status());
        }
        assertTrue(snapshot.successRate() >= 0.0, "成功率可计算");
    }

    @Test
    @DisplayName("重试流量并发：所有请求最终 SUCCESS，超时只存在于事件中")
    void concurrentRetriesAllSucceed() throws Exception {
        int total = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                String traceId = String.format("%032x", 0x80000000L + i);
                tasks.add(() -> {
                    mockMvc.perform(get("/demo/retry")
                                    .param("failAttempts", "2")
                                    .param("attemptDelayMillis", "1")
                                    .param("timeoutMillis", "5")
                                    .header("X-Trace-Id", traceId))
                            .andExpect(status().isOk());
                    return null;
                });
            }
            for (Future<Object> f : pool.invokeAll(tasks)) {
                f.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }

        MetricsSnapshot snapshot = store.snapshot();
        System.out.printf("[ConcurrentRetry] 输入=%d 重试请求(每次前2次超时) | total=%d success=%d "
                        + "timeout计数=%d%n", total, snapshot.totalRequests(),
                snapshot.success(), snapshot.dependencyTimeout());
        assertEquals(total, snapshot.totalRequests());
        assertEquals(total, snapshot.success());
        assertEquals(0, snapshot.dependencyTimeout(),
                "全部被重试挽回，请求级超时计数必须为 0");

        // 每条链路应有 3 个事件：超时、超时、成功
        List<RequestObservation> recent = store.recent(total + 10);
        long retryChains = recent.stream()
                .filter(o -> o.events().size() == 3)
                .filter(o -> o.events().get(0).category() == OutcomeCategory.DEPENDENCY_TIMEOUT)
                .filter(o -> o.events().get(2).category() == OutcomeCategory.SUCCESS)
                .count();
        assertEquals(total, retryChains, "每条重试链路事件序列必须为 超时→超时→成功");
   
    }
}
