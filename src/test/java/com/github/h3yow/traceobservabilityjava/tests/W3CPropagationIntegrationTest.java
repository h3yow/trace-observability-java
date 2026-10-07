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
 * W3C Trace Context 端到端：两种头共存时的唯一取舍、非法/全零 traceparent 的处理、
 * 响应回写最终采用的标识（自定义头 + traceparent 双写），保证日志/指标/响应头三方一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class W3CPropagationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(W3CPropagationIntegrationTest.class);
    private static final Pattern HEX32 = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TP_RESPONSE = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-01$");

    private static final String W3C_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String W3C_SPAN_ID = "00f067aa0ba902b7";
    private static final String VALID_TP = "00-" + W3C_TRACE_ID + "-" + W3C_SPAN_ID + "-01";
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
    @DisplayName("两种头同时合法：采用 W3C 的 traceId，响应双头回写同一套标识")
    void bothHeadersPresentW3cWins() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("X-Trace-Id", CUSTOM_ID)
                        .header("traceparent", VALID_TP))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        String echoedTp = result.getResponse().getHeader("traceparent");
        log.info("[输入] X-Trace-Id={} + traceparent={} | [依据] W3C 优先 => 响应两头都必须是 {}；"
                + "实际 X-Trace-Id={} traceparent={}", CUSTOM_ID, VALID_TP, W3C_TRACE_ID,
                echoedCustom, echoedTp);

        assertEquals(W3C_TRACE_ID, echoedCustom, "自定义响应头必须回写最终采用的 W3C traceId");
        assertNotNull(echoedTp, "响应必须回写 traceparent 供上游关联");
        assertTrue(TP_RESPONSE.matcher(echoedTp).matches(), "回写的 traceparent 必须合法: " + echoedTp);
        assertTrue(echoedTp.contains(W3C_TRACE_ID), "回写 traceparent 必须携带同一 traceId");

        // 日志/指标/明细三方对得上：Observation 挂在同一个 traceId 下且只落定一次
        Observation o = registry.get(W3C_TRACE_ID);
        assertNotNull(o, "观测明细必须挂在最终采用的 traceId 下");
        assertEquals(Outcome.SUCCESS, o.finalOutcome());
        assertNull(registry.get(CUSTOM_ID), "自定义头的值不得再单独产生一条链路");
        assertEquals(1, registry.metrics().totalCount(), "一条请求只能计数一次");
    }

    @Test
    @DisplayName("traceparent 全零（不可信）+ 自定义头合法：回退采用自定义头")
    void allZeroTraceparentFallsBackToCustom() throws Exception {
        String zeroTp = "00-00000000000000000000000000000000-00f067aa0ba902b7-01";
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("X-Trace-Id", CUSTOM_ID)
                        .header("traceparent", zeroTp))
                .andExpect(status().isOk()).andReturn();

        String echoed = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] traceparent={}(全零) + X-Trace-Id={} | [依据] 全零不可信 => 回退自定义头；实际={}",
                zeroTp, CUSTOM_ID, echoed);
        assertEquals(CUSTOM_ID, echoed);
        assertNotNull(registry.get(CUSTOM_ID));
        assertNull(registry.get("00000000000000000000000000000000"), "全零标识不得进入系统");
    }

    @Test
    @DisplayName("traceparent 缺字段 + 自定义头缺失：重新生成并双头回写")
    void malformedTraceparentRegenerates() throws Exception {
        String badTp = "00-4bf92f3577b34da6a3ce929d0e0e4736"; // 缺 parent-id 与 flags
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("traceparent", badTp))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        String echoedTp = result.getResponse().getHeader("traceparent");
        log.info("[输入] traceparent='{}'(缺字段) 无自定义头 | [依据] 非法 => 重新生成；"
                + "实际 X-Trace-Id={} traceparent={}", badTp, echoedCustom, echoedTp);
        assertTrue(HEX32.matcher(echoedCustom).matches());
        assertNotEquals("4bf92f3577b34da6a3ce929d0e0e4736", echoedCustom, "不得沿用不可信片段");
        assertTrue(TP_RESPONSE.matcher(echoedTp).matches());
        assertTrue(echoedTp.contains(echoedCustom), "两个响应头必须是同一套标识");
    }

    @Test
    @DisplayName("仅自定义头合法：行为与旧版一致，同时额外回写 traceparent")
    void customHeaderOnlyKeepsLegacyBehavior() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("X-Trace-Id", CUSTOM_ID))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        String echoedTp = result.getResponse().getHeader("traceparent");
        log.info("[输入] 仅 X-Trace-Id={} | [依据] 旧行为不变 => 沿用；新增 traceparent 回写={}",
                CUSTOM_ID, echoedTp);
        assertEquals(CUSTOM_ID, echoedCustom);
        assertNotNull(registry.get(CUSTOM_ID));
        assertEquals(Outcome.SUCCESS, registry.get(CUSTOM_ID).finalOutcome());
        assertTrue(TP_RESPONSE.matcher(echoedTp).matches());
        assertTrue(echoedTp.contains(CUSTOM_ID));
    }

    @Test
    @DisplayName("仅合法 traceparent：采用其 traceId，父 span 进入上下文")
    void traceparentOnlyAdopted() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("traceparent", VALID_TP))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] 仅 traceparent={} | [依据] W3C 合法 => 采用其 traceId；实际={}",
                VALID_TP, echoedCustom);
        assertEquals(W3C_TRACE_ID, echoedCustom);
        assertNotNull(registry.get(W3C_TRACE_ID));
        assertEquals(1, registry.metrics().totalCount());
    }

    @Test
    @DisplayName("大写 traceparent + 合法自定义头：标准头按非法处理并回退，响应双头回写同一套标识")
    void uppercaseTraceparentFallsBackToCustom() throws Exception {
        // trace-id 整段大写：结构无误，唯一非法原因是 W3C 只允许小写 hex
        String uppercaseTp = "00-" + W3C_TRACE_ID.toUpperCase() + "-" + W3C_SPAN_ID + "-01";
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("X-Trace-Id", CUSTOM_ID)
                        .header("traceparent", uppercaseTp))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        String echoedTp = result.getResponse().getHeader("traceparent");
        log.info("[输入] traceparent='{}'(trace-id 大写) + X-Trace-Id={} | [依据] 大写即非法 => 回退自定义头；"
                + "实际 X-Trace-Id={} traceparent={}", uppercaseTp, CUSTOM_ID, echoedCustom, echoedTp);

        assertEquals(CUSTOM_ID, echoedCustom, "标准头非法时必须回退自定义头");
        assertNotNull(echoedTp);
        assertTrue(TP_RESPONSE.matcher(echoedTp).matches(), "回写的 traceparent 必须是合法小写格式: " + echoedTp);
        assertTrue(echoedTp.contains(CUSTOM_ID), "自定义头与 traceparent 必须回写同一个最终标识");
        // 脏大写 trace-id 既不沿用也不落指标
        assertNull(registry.get(W3C_TRACE_ID), "非法标准头的 trace-id 不得进入指标/明细");
        assertNull(registry.get(W3C_TRACE_ID.toUpperCase()));
        assertNotNull(registry.get(CUSTOM_ID));
        assertEquals(1, registry.metrics().totalCount(), "一条请求只能有一套标识、计数一次");
    }

    @Test
    @DisplayName("混合大小写 traceparent + 无自定义头：重新生成，响应双头同一套且不含入站脏值")
    void mixedCaseTraceparentRegenerates() throws Exception {
        String mixedTp = "00-" + W3C_TRACE_ID + "-" + W3C_SPAN_ID + "-0F"; // flags 一位大写
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("traceparent", mixedTp))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        String echoedTp = result.getResponse().getHeader("traceparent");
        log.info("[输入] traceparent='{}'(flags 含大写) 无自定义头 | [依据] 非法 => 重新生成；"
                + "实际 X-Trace-Id={} traceparent={}", mixedTp, echoedCustom, echoedTp);

        assertTrue(HEX32.matcher(echoedCustom).matches());
        assertNotEquals(W3C_TRACE_ID, echoedCustom, "不得沿用非法标准头中的 trace-id");
        assertTrue(TP_RESPONSE.matcher(echoedTp).matches());
        assertTrue(echoedTp.contains(echoedCustom), "两个响应头必须是同一套标识");
        assertNull(registry.get(W3C_TRACE_ID), "入站非法 trace-id 不得产生观测链路");
        assertEquals(1, registry.metrics().totalCount());
    }

    @Test
    @DisplayName("合法小写 traceparent + 合法自定义头：既有 W3C 优先行为不受大小写收紧影响")
    void lowercaseW3cStillTakesPrecedence() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/demo/sync")
                        .header("X-Trace-Id", CUSTOM_ID)
                        .header("traceparent", VALID_TP))
                .andExpect(status().isOk()).andReturn();

        String echoedCustom = result.getResponse().getHeader("X-Trace-Id");
        log.info("[输入] 小写 traceparent={} + X-Trace-Id={} | [依据] 均合法 => 既有行为不变，W3C 优先；实际={}",
                VALID_TP, CUSTOM_ID, echoedCustom);
        assertEquals(W3C_TRACE_ID, echoedCustom);
        assertNotNull(registry.get(W3C_TRACE_ID));
        assertNull(registry.get(CUSTOM_ID));
    }
}
