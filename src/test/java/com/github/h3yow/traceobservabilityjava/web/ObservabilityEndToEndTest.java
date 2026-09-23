package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.logging.InMemoryLogAppender;
import com.github.h3yow.traceobservabilityjava.observability.MetricsSnapshot;
import com.github.h3yow.traceobservabilityjava.observability.ObservationStore;
import com.github.h3yow.traceobservabilityjava.observability.OutcomeCategory;
import com.github.h3yow.traceobservabilityjava.observability.RequestObservation;
import com.github.h3yow.traceobservabilityjava.observability.SpanEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：真实 Spring MVC 调用链（过滤器→控制器→异常处理→采集）。
 * 每个用例打印输入、响应 traceId 与判定依据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityEndToEndTest {

    private static final String HEADER = "X-Trace-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObservationStore store;

    @BeforeEach
    void resetObservability() {
        store.reset();
        InMemoryLogAppender appender = InMemoryLogAppender.getInstance();
        if (appender != null) {
            appender.reset();
        }
    }

    private static void logCase(String title, Object input, String basis, Object actual) {
        System.out.printf("[E2ETest] %s | 输入=%s | 判定依据=%s | 实际=%s%n",
                title, input, basis, actual);
    }

    @Test
    @DisplayName("成功请求：沿用合法上游标识，归类 SUCCESS，链路/日志/响应头一致")
    void successReusesUpstreamTraceId() throws Exception {
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        MvcResult result = mockMvc.perform(get("/demo/ping").header(HEADER, traceId))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(traceId, result.getResponse().getHeader(HEADER), "响应头必须回显 traceId");

        RequestObservation observation = store.get(traceId);
        assertNotNull(observation);
        assertEquals(OutcomeCategory.SUCCESS, observation.outcome());
        assertTrue(observation.upstreamProvided(), "必须标记为上游提供");
        assertEquals(200, observation.status());
        assertTrue(observation.elapsedNanos() >= 0);

        List<Map<String, Object>> logs = InMemoryLogAppender.getInstance().findByTraceId(traceId);
        assertFalse(logs.isEmpty(), "进程内必须能按 traceId 取回日志");
        assertTrue(logs.stream().allMatch(l -> traceId.equals(l.get("traceId"))),
                "取回的日志必须全部属于该 traceId");
        logCase("成功-沿用上游", "X-Trace-Id=" + traceId,
                "upstreamProvided=true 且 outcome=SUCCESS",
                "events=" + observation.events().size() + ", logs=" + logs.size());
    }

    @Test
    @DisplayName("标识缺失：生成新标识并标记 upstreamProvided=false")
    void missingTraceIdIsGenerated() throws Exception {
        MvcResult result = mockMvc.perform(get("/demo/ping"))
                .andExpect(status().isOk())
                .andReturn();
        String generated = result.getResponse().getHeader(HEADER);
        assertNotNull(generated);
        assertEquals(32, generated.length());

        RequestObservation observation = store.get(generated);
        assertNotNull(observation);
        assertFalse(observation.upstreamProvided());
        assertEquals(OutcomeCategory.SUCCESS, observation.outcome());
        logCase("缺失标识", "无任何追踪头",
                "服务生成 32 位十六进制新标识", generated);
    }

    @Test
    @DisplayName("非法标识（全0/乱码）必须被拒绝并重新生成")
    void illegalTraceIdIsRejected() throws Exception {
        String[] illegal = {"00000000000000000000000000000000", "not-a-trace-id", "XYZ"};
        for (String bad : illegal) {
            MvcResult result = mockMvc.perform(get("/demo/ping").header(HEADER, bad))
                    .andExpect(status().isOk())
                    .andReturn();
            String resolved = result.getResponse().getHeader(HEADER);
            assertNotNull(resolved);
            assertNull(store.get(bad), "非法标识不得产生链路记录: " + bad);
            assertNotNull(store.get(resolved), "新标识必须有对应链路");
            logCase("非法标识", "X-Trace-Id=" + bad, "校验失败 → 生成新标识", resolved);
        }
    }

    @Test
    @DisplayName("业务拒绝：422 + BUSINESS_REJECTED，错误类型可区分")
    void businessRejection() throws Exception {
        String traceId = "11111111111111111111111111111111";
        mockMvc.perform(get("/demo/reject").param("reason", "库存不足").header(HEADER, traceId))
                .andExpect(status().isUnprocessableEntity());

        RequestObservation observation = store.get(traceId);
        assertEquals(OutcomeCategory.BUSINESS_REJECTED, observation.outcome());
        assertEquals("com.github.h3yow.traceobservabilityjava.domain.BusinessRejectionException",
                observation.errorType());
        logCase("业务拒绝", "/demo/reject reason=库存不足",
                "BusinessRejectionException → 422", observation.outcome());
    }

    @Test
    @DisplayName("依赖超时：504 + DEPENDENCY_TIMEOUT，且保留 span 事件")
    void dependencyTimeout() throws Exception {
        String traceId = "22222222222222222222222222222222";
        mockMvc.perform(get("/demo/timeout")
                        .param("delayMillis", "300")
                        .param("timeoutMillis", "50")
                        .header(HEADER, traceId))
                .andExpect(status().isGatewayTimeout());

        RequestObservation observation = store.get(traceId);
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, observation.outcome());
        assertEquals(1, observation.events().size(), "超时尝试必须留下一个 span 事件");
        SpanEvent event = observation.events().get(0);
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, event.category());
        assertEquals(1, event.attempt());
        assertNotNull(event.errorType());
        logCase("依赖超时", "delay=300ms budget=50ms",
                "DependencyTimeoutException → 504，事件数=1",
                observation.outcome() + ", error=" + event.errorType());
    }

    @Test
    @DisplayName("未预期异常：500 + UNEXPECTED_ERROR")
    void unexpectedError() throws Exception {
        String traceId = "33333333333333333333333333333333";
        mockMvc.perform(get("/demo/boom").header(HEADER, traceId))
                .andExpect(status().isInternalServerError());

        RequestObservation observation = store.get(traceId);
        assertEquals(OutcomeCategory.UNEXPECTED_ERROR, observation.outcome());
        assertEquals(IllegalStateException.class.getName(), observation.errorType());
        logCase("未预期异常", "/demo/boom", "IllegalStateException → 500",
                observation.outcome());
    }

    @Test
    @DisplayName("重试：前两次超时事件、最后成功，请求唯一归类仍为 SUCCESS 且只计一次")
    void retryFailuresThenSuccess() throws Exception {
        String traceId = "44444444444444444444444444444444";
        mockMvc.perform(get("/demo/retry")
                        .param("failAttempts", "2")
                        .param("attemptDelayMillis", "5")
                        .param("timeoutMillis", "30")
                        .header(HEADER, traceId))
                .andExpect(status().isOk());

        RequestObservation observation = store.get(traceId);
        assertEquals(OutcomeCategory.SUCCESS, observation.outcome(),
                "中途两次超时不得改变最终归类");
        assertEquals(3, observation.events().size(), "必须有 3 次尝试事件");
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, observation.events().get(0).category());
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, observation.events().get(1).category());
        assertEquals(OutcomeCategory.SUCCESS, observation.events().get(2).category());

        MetricsSnapshot snapshot = store.snapshot();
        assertEquals(1, snapshot.totalRequests(), "整条请求只计数一次");
        assertEquals(1, snapshot.success());
        assertEquals(0, snapshot.dependencyTimeout(), "中途超时不得计入请求级指标");
        logCase("重试成功", "failAttempts=2, budget=30ms",
                "2 个超时事件 + 1 个成功事件，结案 SUCCESS 一次",
                "outcome=" + observation.outcome() + ", total=" + snapshot.totalRequests());
    }

    @Test
    @DisplayName("异步：工作线程内的依赖调用与日志沿用同一 traceId")
    void asyncKeepsTraceAcrossThreads() throws Exception {
        String traceId = "55555555555555555555555555555555";
        // MockMvc 对异步返回值需两段式：先确认异步开始，再 asyncDispatch 取最终响应
        MvcResult asyncResult = mockMvc.perform(get("/demo/async").header(HEADER, traceId))
                .andExpect(request -> assertTrue(
                        request.getRequest().isAsyncStarted(), "必须进入异步处理"))
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains(traceId), "异步返回体必须携带同一 traceId: " + body);

        RequestObservation observation = store.get(traceId);
        assertEquals(OutcomeCategory.SUCCESS, observation.outcome());
        List<Map<String, Object>> logs = waitForWorkerLogs(traceId, 3, 2000);
        assertTrue(logs.size() >= 3, "应至少包含控制器与工作线程日志，实际=" + logs.size());
        boolean workerThreadLogged = logs.stream()
                .map(l -> String.valueOf(l.get("thread")))
                .anyMatch(thread -> thread.startsWith("trace-async-"));
        assertTrue(workerThreadLogged, "必须有来自 trace-async- 工作线程的日志");
        logCase("异步传播", "/demo/async",
                "提交时快照、工作线程恢复，traceId 不变",
                "logs=" + logs.size() + ", 含工作线程日志=" + workerThreadLogged);
    }

    @Test
    @DisplayName("后台任务：响应 200 后，任务仍在同一 traceId 下执行完成")
    void backgroundTaskSharesTraceId() throws Exception {
        String traceId = "66666666666666666666666666666666";
        mockMvc.perform(post("/demo/background").header(HEADER, traceId))
                .andExpect(status().isOk());

        List<Map<String, Object>> logs = waitForCompletionLog(traceId, 2000);
        boolean completion = logs.stream()
                .map(l -> String.valueOf(l.get("message")))
                .anyMatch(m -> m.contains("后台任务完成"));
        assertTrue(completion, "后台任务完成日志必须与请求同 traceId");
        logCase("后台任务", "POST /demo/background",
                "fire-and-forget 任务日志仍可按请求 traceId 关联",
                "logs=" + logs.size());
    }

    @Test
    @DisplayName("线程复用：连续请求在复用的容器线程上无上下文串扰")
    void sequentialRequestsDoNotBleed() throws Exception {
        for (int i = 0; i < 50; i++) {
            String traceId = String.format("%032d", 9000 + i);
            mockMvc.perform(get("/demo/ping").header(HEADER, traceId))
                    .andExpect(status().isOk())
                    .andExpect(r -> assertEquals(traceId, r.getResponse().getHeader(HEADER)));
            RequestObservation observation = store.get(traceId);
            assertEquals(traceId, observation.traceId());
            assertEquals(OutcomeCategory.SUCCESS, observation.outcome());
        }
        MetricsSnapshot snapshot = store.snapshot();
        assertEquals(50, snapshot.totalRequests());
        assertEquals(50, snapshot.success());
        assertEquals(0, snapshot.activeRequests(), "所有请求必须结案，无泄漏的活跃计数");
        logCase("连续 50 请求", "复用 Tomcat 线程处理不同 traceId",
                "每请求各自结案，指标恰好 50", "total=" + snapshot.totalRequests());
    }

    private List<Map<String, Object>> waitForWorkerLogs(String traceId, int minSize, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<Map<String, Object>> logs;
        do {
            logs = InMemoryLogAppender.getInstance().findByTraceId(traceId);
            if (logs.size() >= minSize) {
                return logs;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        return logs;
    }

    private List<Map<String, Object>> waitForCompletionLog(String traceId, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        List<Map<String, Object>> logs;
        do {
            logs = InMemoryLogAppender.getInstance().findByTraceId(traceId);
            boolean done = logs.stream()
                    .map(l -> String.valueOf(l.get("message")))
                    .anyMatch(m -> m.contains("后台任务完成"));
            if (done) {
                return logs;
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        return logs;
    }
}
