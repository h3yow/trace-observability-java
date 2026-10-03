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

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 双追踪头共存时的取舍规则（端到端）：
 * traceparent 合法 > 自定义头合法 > 重新生成；响应回写最终采用的标识（两种头都写）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class W3cTraceContextIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(W3cTraceContextIntegrationTest.class);
    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TRACEPARENT =
            Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");

    private static final String W3C_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String W3C_VALID = "00-" + W3C_TRACE_ID + "-00f067aa0ba902b7-01";
    private static final String CUSTOM_ID = "aabbccddeeff00112233445566778899";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObservationRegistry registry;

    @BeforeEach
    void reset() {
        registry.reset();
    }

    @Test
    @DisplayName("两种头都合法时：traceparent 优先，且只产生一套标识")
    void w3cWinsWhenBothValid() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("traceparent", W3C_VALID)
                        .header("X-Trace-Id", CUSTOM_ID))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        String echoedParent = result.getResponse().getHeader("traceparent");
        log.info("[输入] traceparent={} + X-Trace-Id={} | [依据] 取舍规则=标准头优先 => "
                + "期望采用 {}，自定义头回写同一值", W3C_VALID, CUSTOM_ID, W3C_TRACE_ID);

        assertEquals(W3C_TRACE_ID, echoedCustom, "自定义响应头必须回写最终采用的标识");
        assertNotNull(echoedParent);
        assertTrue(TRACEPARENT.matcher(echoedParent).matches(),
                "响应必须回写合法 traceparent，实际=" + echoedParent);
        assertTrue(echoedParent.contains(W3C_TRACE_ID), "回写的 traceparent 必须含最终 traceId");

        // 只有一套标识：自定义头的值不应出现在注册表里
        assertNull(registry.get(CUSTOM_ID), "被舍弃的自定义头标识不得产生观测记录");
        Observation o = registry.get(W3C_TRACE_ID);
        assertNotNull(o);
        assertEquals(Outcome.SUCCESS, o.finalOutcome());
        assertEquals(1, registry.metrics().totalCount(), "一条请求只能计数一次");
    }

    @Test
    @DisplayName("traceparent 非法（全零 trace-id）时：回退到合法自定义头")
    void fallsBackToCustomWhenW3cInvalid() throws Exception {
        String zeroW3c = "00-00000000000000000000000000000000-00f067aa0ba902b7-01";
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("traceparent", zeroW3c)
                        .header("X-Trace-Id", CUSTOM_ID))
                .andExpect(status().isOk()).andReturn();

        String echoed = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] traceparent={}（全零）+ X-Trace-Id={} | [依据] 全零不可信 => 回退自定义头",
                zeroW3c, CUSTOM_ID);
        assertEquals(CUSTOM_ID, echoed);
        assertNotNull(registry.get(CUSTOM_ID));
    }

    @Test
    @DisplayName("traceparent 缺字段且自定义头也非法时：重新生成合法标识并回写")
    void regeneratesWhenBothInvalid() throws Exception {
        String brokenW3c = "00-4bf92f3577b34da6a3ce929d0e0e4736"; // 缺字段
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("traceparent", brokenW3c)
                        .header("X-Trace-Id", "evil-injected!!!"))
                .andExpect(status().isOk()).andReturn();

        String echoed = result.getResponse().getHeader("X-Trace-Id");
        String echoedParent = result.getResponse().getHeader("traceparent");
        log.info("[输入] traceparent='{}'（缺字段）+ X-Trace-Id 非法 | [依据] 均不可信 => "
                + "重新生成，实际={}", brokenW3c, echoed);
        assertNotNull(echoed);
        assertTrue(HEX32.matcher(echoed).matches(), "必须生成 32 位小写 hex，实际=" + echoed);
        assertNotEquals("evil-injected!!!", echoed);
        assertTrue(TRACEPARENT.matcher(echoedParent).matches());
        assertTrue(echoedParent.contains(echoed), "两种响应头必须携带同一个最终标识");
        assertNotNull(registry.get(echoed));
    }

    @Test
    @DisplayName("仅 traceparent（无自定义头）时：采用其 trace-id，父 span 被记录")
    void adoptsW3cOnly() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("traceparent", W3C_VALID))
                .andExpect(status().isOk()).andReturn();

        String echoed = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] 仅 traceparent={} | [依据] 合法即采用 => 期望 traceId={}",
                W3C_VALID, W3C_TRACE_ID);
        assertEquals(W3C_TRACE_ID, echoed);
        Observation o = registry.get(W3C_TRACE_ID);
        assertNotNull(o);
        assertEquals(Outcome.SUCCESS, o.finalOutcome());
    }

    @Test
    @DisplayName("仅合法自定义头（无 traceparent）时：原行为不变，同时回写 traceparent")
    void customHeaderBehaviorUnchanged() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("X-Trace-Id", CUSTOM_ID))
                .andExpect(status().isOk()).andReturn();

        String echoed = result.getResponse().getHeader("X-Trace-Id");
        String echoedParent = result.getResponse().getHeader("traceparent");
        log.info("[输入] 仅 X-Trace-Id={} | [依据] 原行为沿用 => 期望回写同一值 + 标准头", CUSTOM_ID);
        assertEquals(CUSTOM_ID, echoed);
        assertNotNull(echoedParent);
        assertTrue(echoedParent.contains(CUSTOM_ID));
        assertNotNull(registry.get(CUSTOM_ID));
    }
}
