package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.observation.MetricsStore;
import com.github.h3yow.traceobservabilityjava.observation.Observation;
import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端：真实过滤器 + 控制器 + 异常处理 + 线程池 + 进程内指标。
 * 每个用例打印输入、traceId、最终归类与判定依据，并对计数做对账。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HttpObservabilityIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HttpObservabilityIntegrationTest.class);
    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObservationRegistry registry;

    @BeforeEach
    void resetMetrics() {
        registry.reset();
    }

    @Test
    @DisplayName("正常完成 => SUCCESS，且响应头回写同一 traceId")
    void successCase() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync").param("scenario", "success"))
                .andExpect(status().isOk()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        Observation o = registry.get(traceId);
        log.info("[输入] GET /api/demo/sync?scenario=success | traceId={} | [依据] 2xx 无异常 => {}，settled={}",
                traceId, o.finalOutcome(), o.isSettled());
        assertTrue(HEX32.matcher(traceId).matches());
        assertEquals(Outcome.SUCCESS, o.finalOutcome());
        assertEquals(1, registry.metrics().count(Outcome.SUCCESS));
        assertEquals(1, registry.metrics().totalCount(), "该请求只能计数一次");
    }

    @Test
    @DisplayName("上游合法 traceId 被沿用，出/入站一致")
    void inboundTraceIdReused() throws Exception {
        String inbound = "00112233445566778899aabbccddeeff";
        MvcResult result = mockMvc.perform(get("/api/demo/sync").header("X-Trace-Id", inbound))
                .andExpect(status().isOk()).andReturn();
        String echoed = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] X-Trace-Id={} | [依据] 合法 => 响应头与 Observation 必须沿用", inbound);
        assertEquals(inbound, echoed);
        assertNotNull(registry.get(inbound));
        assertEquals(Outcome.SUCCESS, registry.get(inbound).finalOutcome());
    }

    @Test
    @DisplayName("上游非法 traceId 被替换为新生成的合法标识")
    void invalidInboundTraceIdRegenerated() throws Exception {
        String inbound = "not-a-valid-id!!!";
        MvcResult result = mockMvc.perform(get("/api/demo/sync").header("X-Trace-Id", inbound))
                .andExpect(status().isOk()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] X-Trace-Id='{}' | [依据] 非法 => 生成新标识 {} 并沿用全链路", inbound, traceId);
        assertNotEquals(inbound, traceId);
        assertTrue(HEX32.matcher(traceId).matches());
    }

    @Test
    @DisplayName("业务拒绝 => 422 + BUSINESS_REJECTED")
    void rejectedCase() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync").param("scenario", "reject"))
                .andExpect(status().isUnprocessableEntity()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        Observation o = registry.get(traceId);
        log.info("[输入] scenario=reject | traceId={} status=422 | [依据] BusinessRejectedException => {}",
                traceId, o.finalOutcome());
        assertEquals(Outcome.BUSINESS_REJECTED, o.finalOutcome());
        assertEquals(1, registry.metrics().count(Outcome.BUSINESS_REJECTED));
    }

    @Test
    @DisplayName("依赖超时 => 504 + DEPENDENCY_TIMEOUT")
    void timeoutCase() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .param("scenario", "timeout").param("timeoutMillis", "10"))
                .andExpect(status().isGatewayTimeout()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        Observation o = registry.get(traceId);
        log.info("[输入] scenario=timeout timeoutMillis=10 | traceId={} status=504 | "
                + "[依据] DependencyTimeoutException => {}", traceId, o.finalOutcome());
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, o.finalOutcome());
        assertEquals(1, registry.metrics().count(Outcome.DEPENDENCY_TIMEOUT));
    }

    @Test
    @DisplayName("未预期异常 => 500 + UNEXPECTED_ERROR")
    void bugCase() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync").param("scenario", "bug"))
                .andExpect(status().isInternalServerError()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        Observation o = registry.get(traceId);
        log.info("[输入] scenario=bug | traceId={} status=500 | [依据] IllegalStateException => {}",
                traceId, o.finalOutcome());
        assertEquals(Outcome.UNEXPECTED_ERROR, o.finalOutcome());
        assertEquals(1, registry.metrics().count(Outcome.UNEXPECTED_ERROR));
    }

    @Test
    @DisplayName("异步线程池执行：工作线程读到同一 traceId，归类仍唯一")
    void asyncPropagation() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/async").param("scenario", "success"))
                .andExpect(status().isOk()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        Observation o = registry.get(traceId);
        log.info("[输入] /api/demo/async | traceId={} | [依据] 线程池快照传播 + 2xx => SUCCESS，attempts={}",
                traceId, o.attemptCount());
        assertEquals(Outcome.SUCCESS, o.finalOutcome());
        assertTrue(o.attemptCount() >= 1, "异步依赖尝试必须被记录到同一 Observation");
    }

    @Test
    @DisplayName("异步链路里依赖超时仍归类 DEPENDENCY_TIMEOUT")
    void asyncTimeout() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/async")
                        .param("scenario", "timeout").param("timeoutMillis", "10"))
                .andExpect(status().isGatewayTimeout()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] /api/demo/async timeout | traceId={} | [依据] 包装异常链含超时 => DEPENDENCY_TIMEOUT",
                traceId);
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, registry.get(traceId).finalOutcome());
    }

    @Test
    @DisplayName("重试：前1次超时后成功 => 最终 SUCCESS 只计一次，但保留 2 条尝试明细")
    void retryTimeoutThenSuccess() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/retry")
                        .param("maxAttempts", "3").param("failFirstN", "1")
                        .param("timeoutMillis", "10"))
                .andExpect(status().isOk()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        Observation o = registry.get(traceId);
        List<Observation.Attempt> attempts = o.attempts();
        log.info("[输入] retry maxAttempts=3 failFirstN=1 | traceId={} | [依据] 请求最终 2xx => SUCCESS；"
                + "尝试明细 attempts={}（含 1 次 timeout + 1 次 success）", traceId, attempts.size());
        assertEquals(Outcome.SUCCESS, o.finalOutcome(), "重试后成功，最终归类必须是 SUCCESS");
        assertEquals(2, attempts.size(), "每次真实尝试各一条，稳定 attemptId 去重");
        assertEquals(1, registry.metrics().totalCount(), "无论内部重试几次，请求只计数一次");
        assertTrue(attempts.stream().anyMatch(a -> a.outcome() == Outcome.DEPENDENCY_TIMEOUT));
        assertTrue(attempts.stream().anyMatch(a -> a.outcome() == Outcome.SUCCESS));
    }

    @Test
    @DisplayName("重试全部超时 => DEPENDENCY_TIMEOUT，且明细为全部尝试次数")
    void retryAllTimeout() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/retry")
                        .param("maxAttempts", "3").param("failFirstN", "3")
                        .param("timeoutMillis", "10"))
                .andExpect(status().isGatewayTimeout()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        Observation o = registry.get(traceId);
        log.info("[输入] retry failFirstN=3 | traceId={} | [依据] 最终异常为超时 => DEPENDENCY_TIMEOUT，attempts={}",
                traceId, o.attemptCount());
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, o.finalOutcome());
        assertEquals(3, o.attemptCount());
        assertEquals(1, registry.metrics().totalCount());
    }

    @Test
    @DisplayName("后台任务提交后立即返回：请求 SUCCESS，后台仍挂同一 traceId")
    void backgroundKeepsTrace() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/background").param("scenario", "success"))
                .andExpect(status().isOk()).andReturn();
        String traceId = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] /api/demo/background | traceId={} | [依据] 提交即 2xx => SUCCESS；"
                + "后台任务携带同一 traceId（见 JSON 日志 worker_context_ok）", traceId);
        assertEquals(Outcome.SUCCESS, registry.get(traceId).finalOutcome());
        // 给后台任务一点时间完成，其尝试也应聚合到同一 Observation（不改变最终归类）
        long deadline = System.currentTimeMillis() + 3000;
        while (registry.get(traceId).attemptCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(registry.get(traceId).attemptCount() >= 1);
        assertEquals(Outcome.SUCCESS, registry.get(traceId).finalOutcome(),
                "迟到的后台尝试不得覆盖请求已落定的结论");
    }
}
