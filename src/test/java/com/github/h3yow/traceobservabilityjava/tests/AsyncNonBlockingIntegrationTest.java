package com.github.h3yow.traceobservabilityjava.tests;

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
 * 真异步（非阻塞）链路：
 * 入口线程在异步启动后即释放（asyncStarted），结果由工作线程算完后异步派发写回；
 * 执行/回调/异常/最终归类全程同一 traceId，最终归类只落定一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsyncNonBlockingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AsyncNonBlockingIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObservationRegistry registry;

    @BeforeEach
    void reset() {
        registry.reset();
    }

    @Test
    @DisplayName("异步成功：入口线程提前释放，最终归类 SUCCESS 且只计数一次")
    void asyncSuccess() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/demo/async-nonblocking")
                        .param("scenario", "success"))
                .andExpect(request().asyncStarted()) // 入口线程已释放，未等结果
                .andReturn();
        String traceId = started.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] GET /api/demo/async-nonblocking?scenario=success | traceId={} | "
                + "[依据] asyncStarted=true 说明入口线程未阻塞", traceId);
        assertNotNull(traceId);
        assertFalse(registry.get(traceId).isSettled(),
                "异步未完成前不得提前落定结论");

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk()).andReturn();
        String echoed = completed.getResponse().getHeader("X-Trace-Id");

        Observation o = registry.get(traceId);
        log.info("[判定] traceId={} settled={} outcome={} attempts={} total={}",
                traceId, o.isSettled(), o.finalOutcome(), o.attemptCount(),
                registry.metrics().totalCount());
        assertEquals(traceId, echoed, "异步派发后响应头仍是同一标识");
        assertTrue(o.isSettled());
        assertEquals(Outcome.SUCCESS, o.finalOutcome());
        assertEquals(200, o.finalHttpStatus());
        assertTrue(o.attemptCount() >= 1, "工作线程上的尝试必须聚合到同一 Observation");
        assertEquals(1, registry.metrics().count(Outcome.SUCCESS));
        assertEquals(1, registry.metrics().totalCount(), "跨线程也只能计数一次");
    }

    @Test
    @DisplayName("异步异常：依赖超时跨线程传导，最终归类 DEPENDENCY_TIMEOUT 且只计数一次")
    void asyncFailure() throws Exception {
        MvcResult started = mockMvc.perform(get("/api/demo/async-nonblocking")
                        .param("scenario", "timeout").param("timeoutMillis", "10"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String traceId = started.getResponse().getHeader("X-Trace-Id");

        MvcResult completed = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isGatewayTimeout()).andReturn();

        Observation o = registry.get(traceId);
        log.info("[输入] scenario=timeout&timeoutMillis=10 | traceId={} | [依据] "
                + "DependencyTimeoutException 经异步派发 => 504/DEPENDENCY_TIMEOUT，实际={}/{}",
                traceId, completed.getResponse().getStatus(), o.finalOutcome());
        assertTrue(o.isSettled());
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, o.finalOutcome());
        assertEquals(504, o.finalHttpStatus());
        assertEquals(1, registry.metrics().count(Outcome.DEPENDENCY_TIMEOUT));
        assertEquals(1, registry.metrics().totalCount(), "异常路径也只能计数一次");
    }

    @Test
    @DisplayName("并发异步混合：全部请求落定后指标守恒，无重复计数")
    void asyncConcurrentConservation() throws Exception {
        int each = 8;
        MvcResult[] pending = new MvcResult[each * 2];
        for (int i = 0; i < each; i++) {
            pending[i] = mockMvc.perform(get("/api/demo/async-nonblocking")
                            .param("scenario", "success"))
                    .andExpect(request().asyncStarted()).andReturn();
            pending[each + i] = mockMvc.perform(get("/api/demo/async-nonblocking")
                            .param("scenario", "reject"))
                    .andExpect(request().asyncStarted()).andReturn();
        }
        for (int i = 0; i < each; i++) {
            mockMvc.perform(asyncDispatch(pending[i])).andExpect(status().isOk());
            mockMvc.perform(asyncDispatch(pending[each + i]))
                    .andExpect(status().isUnprocessableEntity());
        }
        long success = registry.metrics().count(Outcome.SUCCESS);
        long rejected = registry.metrics().count(Outcome.BUSINESS_REJECTED);
        long total = registry.metrics().totalCount();
        log.info("[输入] {} 成功 + {} 业务拒绝并发异步 | [依据] 守恒式 total==SUCCESS+REJECTED=={}，"
                + "实际 {}/{}/{}", each, each, each * 2L, success, rejected, total);
        assertEquals(each, success);
        assertEquals(each, rejected);
        assertEquals(each * 2L, total, "每个请求恰好计数一次");
        assertEquals(each / (double) (each * 2), registry.metrics().successRate(), 1e-9);
    }
}
