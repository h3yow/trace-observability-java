package com.github.h3yow.traceobservabilityjava.tests;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /internal/metrics 的分位数与成功率：空数据不 NaN、有数据后与计数严格对账。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsPercentileEndpointTest {

    private static final Logger log = LoggerFactory.getLogger(MetricsPercentileEndpointTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObservationRegistry registry;

    @BeforeEach
    void reset() {
        registry.reset();
    }

    @Test
    @DisplayName("空数据：successRate=0.0，各分位数为 0，无 NaN")
    void emptyMetrics() throws Exception {
        MvcResult result = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        log.info("[输入] 空指标 GET /internal/metrics | [依据] 空数据返回 0 值 => {}", body);
        assertTrue(body.contains("\"successRate\":0.0"), "空数据成功率必须为 0.0: " + body);
        assertTrue(body.contains("\"p50Nanos\":0"), body);
        assertFalse(body.contains("NaN"), "响应中不得出现 NaN");
    }

    @Test
    @DisplayName("混合请求后：successRate 与分位数样本量和计数对账")
    void percentilesConsistentWithCounts() throws Exception {
        mockMvc.perform(get("/api/demo/sync").param("scenario", "success"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/sync").param("scenario", "success"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/demo/sync").param("scenario", "reject"))
                .andExpect(status().isUnprocessableEntity());

        MvcResult result = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        String body = result.getResponse().getContentAsString();
        long total = registry.metrics().totalCount();
        long success = registry.metrics().count(Outcome.SUCCESS);
        long overallSeen = registry.metrics().overallSamples().seen();
        log.info("[输入] 2xsuccess + 1xreject | [依据] successRate=2/3、样本数==计数 => "
                + "total={} success={} overallSeen={} body={}", total, success, overallSeen, body);

        // 注意：/internal/metrics 这次请求本身也被观测，且在响应体生成之后才落定，
        // 因此 body 反映的是 3 条业务请求（successRate=2/3），
        // 而请求返回后注册表状态包含它自己（total=4、SUCCESS=3、successRate=3/4）。
        assertEquals(4L, total, "3 条业务请求 + /internal/metrics 自观测");
        assertEquals(4L, overallSeen, "整体样本数必须与总请求数一致");
        assertEquals(3L, registry.metrics().samples(Outcome.SUCCESS).seen());
        assertEquals(1L, registry.metrics().samples(Outcome.BUSINESS_REJECTED).seen());
        assertEquals(3 / 4.0d, registry.metrics().successRate(), 1e-9);
        assertTrue(body.contains("\"successRate\":0.666"), "响应体反映落定前的 2/3: " + body);
        assertTrue(body.contains("\"p95Nanos\""), "响应必须包含分位数字段: " + body);
        assertFalse(body.contains("NaN"));
    }
}
