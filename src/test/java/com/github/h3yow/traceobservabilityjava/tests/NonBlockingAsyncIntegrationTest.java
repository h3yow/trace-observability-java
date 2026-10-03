package com.github.h3yow.traceobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真·非阻塞异步链路：入口线程立即释放（asyncStarted），结果由后台线程算完后写响应。
 * 验证跨线程全程同一条链路：执行、完成回调、异常路径的 traceId 一致，
 * 最终归类有且仅有一个，指标恰好计数一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class NonBlockingAsyncIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NonBlockingAsyncIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObservationRegistry registry;

    @BeforeEach
    void reset() {
        registry.reset();
    }

    @Test
    @DisplayName("非阻塞成功：入口线程不等待，工作线程同链路完成，归类 SUCCESS 仅一次")
    void nonblockingSuccess() throws Exception {
        MvcResult pending = mockMvc.perform(get("/api/demo/nonblocking")
                        .param("scenario", "success"))
                .andExpect(request().asyncStarted()) // 关键证据：入口线程已释放，未等结果
                .andReturn();
        String traceId = pending.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] GET /api/demo/nonblocking?scenario=success | [依据] asyncStarted=true "
                + "说明请求线程未被占用等待；traceId={}", traceId);

        MvcResult done = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = MAPPER.readTree(done.getResponse().getContentAsByteArray());
        String worker = body.path("worker").asText();
        String bodyTraceId = body.path("traceId").asText();
        log.info("[依据] 响应由工作线程 {} 写完，body.traceId={} 必须等于响应头 traceId={}",
                worker, bodyTraceId, traceId);

        assertEquals(traceId, bodyTraceId, "跨线程后业务看到的 traceId 必须与入口一致");
        assertTrue(worker.startsWith("pool"), "响应应由传播了上下文的工作线程写出，实际=" + worker);

        Observation o = registry.get(traceId);
        assertNotNull(o);
        assertTrue(o.isSettled(), "异步完成后必须已落定");
        assertEquals(Outcome.SUCCESS, o.finalOutcome());
        assertEquals(200, o.finalHttpStatus());
        assertTrue(o.attemptCount() >= 1, "工作线程的尝试必须聚合到同一 Observation");
        assertEquals(1, registry.metrics().count(Outcome.SUCCESS));
        assertEquals(1, registry.metrics().totalCount(), "跨线程也只能计数一次");
    }

    @Test
    @DisplayName("非阻塞超时：异步异常经回调归类 DEPENDENCY_TIMEOUT，traceId 全链路一致")
    void nonblockingTimeout() throws Exception {
        MvcResult pending = mockMvc.perform(get("/api/demo/nonblocking")
                        .param("scenario", "timeout").param("timeoutMillis", "10"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String traceId = pending.getResponse().getHeader("X-Trace-Id");

        MvcResult done = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isGatewayTimeout()).andReturn();
        JsonNode body = MAPPER.readTree(done.getResponse().getContentAsByteArray());
        log.info("[输入] scenario=timeout timeoutMillis=10 | [依据] 异步依赖超时 => 504/"
                + "DEPENDENCY_TIMEOUT；body.traceId={} 响应头 traceId={}",
                body.path("traceId").asText(), traceId);

        assertEquals(traceId, body.path("traceId").asText(),
                "异步异常路径的响应体 traceId 必须与入口一致（异步 dispatch 线程无 ThreadLocal，靠请求属性兜底）");

        Observation o = registry.get(traceId);
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, o.finalOutcome());
        assertEquals(504, o.finalHttpStatus());
        assertEquals(1, registry.metrics().count(Outcome.DEPENDENCY_TIMEOUT));
        assertEquals(1, registry.metrics().totalCount(), "异常路径同样只计数一次");
    }

    @Test
    @DisplayName("非阻塞未预期异常：归类 UNEXPECTED_ERROR，且不会被迟到结论覆盖")
    void nonblockingBug() throws Exception {
        MvcResult pending = mockMvc.perform(get("/api/demo/nonblocking")
                        .param("scenario", "bug"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String traceId = pending.getResponse().getHeader("X-Trace-Id");

        mockMvc.perform(asyncDispatch(pending)).andExpect(status().isInternalServerError());

        Observation o = registry.get(traceId);
        log.info("[输入] scenario=bug | [依据] IllegalStateException => UNEXPECTED_ERROR；"
                + "settled={} outcome={}", o.isSettled(), o.finalOutcome());
        assertEquals(Outcome.UNEXPECTED_ERROR, o.finalOutcome());
        assertEquals(1, registry.metrics().count(Outcome.UNEXPECTED_ERROR));
        assertEquals(1, registry.metrics().totalCount());

        // 模拟迟到结论：异步已完成并落定后，任何二次 settle 都必须被拒绝
        boolean late = o.settleFinal(Outcome.SUCCESS, 1L, 200);
        log.info("[依据] 迟到 settle(SUCCESS) 返回值={}，必须 false 且不改变已定结论", late);
        assertFalse(late);
        assertEquals(Outcome.UNEXPECTED_ERROR, o.finalOutcome());
        assertEquals(1, registry.metrics().totalCount(), "迟到结论不得再次计数");
    }

    @Test
    @DisplayName("非阻塞链路沿用 W3C traceparent：跨线程后仍是同一条上游链路")
    void nonblockingKeepsW3cTrace() throws Exception {
        String tp = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        MvcResult pending = mockMvc.perform(get("/api/demo/nonblocking")
                        .param("scenario", "success").header("traceparent", tp))
                .andExpect(request().asyncStarted())
                .andReturn();
        String traceId = pending.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] traceparent={} | [依据] W3C 合法 => 异步全程沿用 {}", tp, traceId);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", traceId);

        MvcResult done = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = MAPPER.readTree(done.getResponse().getContentAsByteArray());
        assertEquals(traceId, body.path("traceId").asText());
        assertEquals(Outcome.SUCCESS, registry.get(traceId).finalOutcome());
        assertEquals(1, registry.metrics().totalCount());
    }
}
