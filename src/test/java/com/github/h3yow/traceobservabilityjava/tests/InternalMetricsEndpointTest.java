package com.github.h3yow.traceobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证采集结果可在进程内直接获取：发若干请求后读取 /internal/metrics，
 * total 必须等于各分类之和，并能按 traceId 拉到单条链路明细。
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalMetricsEndpointTest {

    private static final Logger log = LoggerFactory.getLogger(InternalMetricsEndpointTest.class);
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
    @DisplayName("/internal/metrics 数字守恒，/internal/observation 可解释单链路")
    void metricsAndSingleObservation() throws Exception {
        // 2 success + 1 reject + 1 timeout + 1 bug
        hit("/api/demo/sync", "success", "100");
        hit("/api/demo/sync", "success", "100");
        hit("/api/demo/sync", "reject", "100");
        String timeoutTrace = hit("/api/demo/sync", "timeout", "10");
        hit("/api/demo/sync", "bug", "100");

        MvcResult result = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        long total = json.path("total").asLong();
        JsonNode by = json.path("byOutcome");
        long sum = by.path("SUCCESS").path("count").asLong()
                + by.path("BUSINESS_REJECTED").path("count").asLong()
                + by.path("DEPENDENCY_TIMEOUT").path("count").asLong()
                + by.path("UNEXPECTED_ERROR").path("count").asLong();
        log.info("[判定依据] /internal/metrics total={} 分类求和={} (S={},R={},T={},U={})",
                total, sum,
                by.path("SUCCESS").path("count").asLong(),
                by.path("BUSINESS_REJECTED").path("count").asLong(),
                by.path("DEPENDENCY_TIMEOUT").path("count").asLong(),
                by.path("UNEXPECTED_ERROR").path("count").asLong());
        assertEquals(5, total);
        assertEquals(5, sum);

        MvcResult one = mockMvc.perform(get("/internal/observation").param("traceId", timeoutTrace))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = MAPPER.readTree(one.getResponse().getContentAsByteArray());
        log.info("[判定依据] 单链路 traceId={} found={} finalOutcome={} httpStatus={} attempts={}",
                timeoutTrace, node.path("found").asBoolean(),
                node.path("finalOutcome").asText(), node.path("httpStatus").asInt(),
                node.path("attemptCount").asInt());
        assertTrue(node.path("found").asBoolean());
        assertEquals("DEPENDENCY_TIMEOUT", node.path("finalOutcome").asText());
        assertEquals(504, node.path("httpStatus").asInt());
    }

    private String hit(String path, String scenario, String timeout) throws Exception {
        MvcResult r = mockMvc.perform(get(path)
                        .param("scenario", scenario).param("timeoutMillis", timeout))
                .andReturn();
        return r.getResponse().getHeader("X-Trace-Id");
    }
}
