package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.trace.TracePropagator;
import com.github.h3yow.traceobservabilityjava.trace.Traceparent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3C traceparent 解析与两种来源取舍规则的单测。
 * 每条用例打印 [输入] 与 [依据]，失败时能直接区分原因。
 */
class W3CTraceparentTest {

    private static final Logger log = LoggerFactory.getLogger(W3CTraceparentTest.class);

    private static final String VALID_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String VALID_SPAN_ID = "00f067aa0ba902b7";
    private static final String VALID_TP = "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01";
    private static final String CUSTOM_ID = "aabbccddeeff00112233445566778899";

    private final TracePropagator propagator =
            new TracePropagator("X-Trace-Id", "^[0-9a-fA-F]{16,32}$", 32);

    @Test
    @DisplayName("合法 traceparent 解析出 traceId/parentSpanId/sampled")
    void parsesValidTraceparent() {
        Traceparent tp = Traceparent.parse(VALID_TP);
        log.info("[输入] traceparent='{}' | [依据] 4 段、version=00、id 非全零 => 解析成功", VALID_TP);
        assertNotNull(tp);
        assertEquals(VALID_TRACE_ID, tp.traceId());
        assertEquals(VALID_SPAN_ID, tp.parentSpanId());
        assertTrue(tp.sampled(), "flags=01 最低位为采样位");
    }

    @Test
    @DisplayName("缺字段/多字段/空值的 traceparent 一律无效")
    void rejectsMalformedShape() {
        String[] invalid = {
                null, "", "   ",
                "00",                                            // 只有 version
                "00-" + VALID_TRACE_ID,                          // 缺 parent/flags
                "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID,    // 缺 flags
                VALID_TP + "-extra",                             // 多一段
                "0-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01",   // version 长度错
                "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-1",   // flags 长度错
        };
        for (String raw : invalid) {
            Traceparent tp = Traceparent.parse(raw);
            log.info("[输入] traceparent='{}' | [依据] 段数/字段长度不符 => 期望 null，实际={}",
                    raw, tp == null ? "null(无效)" : tp);
            assertNull(tp, "应当无效: " + raw);
        }
    }

    @Test
    @DisplayName("非十六进制字符的 traceparent 无效")
    void rejectsNonHex() {
        String[] invalid = {
                "00-zzzz2f3577b34da6a3ce929d0e0e4736-" + VALID_SPAN_ID + "-01",
                "00-" + VALID_TRACE_ID + "-zzf067aa0ba902b7-01",
                "0g-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01",
                "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-0x",
        };
        for (String raw : invalid) {
            Traceparent tp = Traceparent.parse(raw);
            log.info("[输入] traceparent='{}' | [依据] 含非 hex 字符 => 期望 null", raw);
            assertNull(tp, "应当无效: " + raw);
        }
    }

    @Test
    @DisplayName("全零 trace-id / 全零 parent-id 属于不可信值，必须按无效处理")
    void rejectsAllZeroIds() {
        String zeroTrace = "00-00000000000000000000000000000000-" + VALID_SPAN_ID + "-01";
        String zeroSpan = "00-" + VALID_TRACE_ID + "-0000000000000000-01";
        log.info("[输入] traceparent='{}' | [依据] trace-id 全零 => 不可信，期望 null", zeroTrace);
        assertNull(Traceparent.parse(zeroTrace));
        log.info("[输入] traceparent='{}' | [依据] parent-id 全零 => 不可信，期望 null", zeroSpan);
        assertNull(Traceparent.parse(zeroSpan));
    }

    @Test
    @DisplayName("version=ff 为规范保留的非法值，必须拒绝")
    void rejectsVersionFf() {
        String raw = "ff-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01";
        log.info("[输入] traceparent='{}' | [依据] version=ff 规范明令非法 => 期望 null", raw);
        assertNull(Traceparent.parse(raw));
    }

    @Test
    @DisplayName("取舍规则：traceparent 与自定义头同时合法时，W3C 优先且父 span 被采用")
    void w3cWinsWhenBothValid() {
        TracePropagator.InboundDecision d = propagator.decide(CUSTOM_ID, VALID_TP);
        log.info("[输入] X-Trace-Id={} + traceparent={} | [依据] 两者合法 => W3C 优先，"
                + "traceId 取 traceparent 的，parentSpanId 取其父 span", CUSTOM_ID, VALID_TP);
        assertEquals(TracePropagator.Source.W3C, d.source());
        assertEquals(VALID_TRACE_ID, d.traceId());
        assertEquals(VALID_SPAN_ID, d.parentSpanId());
    }

    @Test
    @DisplayName("取舍规则：traceparent 非法时回退到合法自定义头")
    void fallsBackToCustomWhenTraceparentInvalid() {
        String badTp = "00-00000000000000000000000000000000-" + VALID_SPAN_ID + "-01";
        TracePropagator.InboundDecision d = propagator.decide(CUSTOM_ID, badTp);
        log.info("[输入] X-Trace-Id={} + traceparent={}(全零) | [依据] W3C 无效 => 回退自定义头",
                CUSTOM_ID, badTp);
        assertEquals(TracePropagator.Source.UPSTREAM, d.source());
        assertEquals(CUSTOM_ID, d.traceId());
        assertNull(d.parentSpanId());
    }

    @Test
    @DisplayName("取舍规则：两者都非法/缺失时重新生成，且不与入站脏值相同")
    void regeneratesWhenBothInvalid() {
        TracePropagator.InboundDecision d = propagator.decide("evil-id!!", "not-a-traceparent");
        log.info("[输入] X-Trace-Id='evil-id!!' + traceparent='not-a-traceparent' | "
                + "[依据] 均非法 => 生成 32 位小写 hex，实际={}", d.traceId());
        assertEquals(TracePropagator.Source.GENERATED, d.source());
        assertTrue(d.traceId().matches("^[0-9a-f]{32}$"));
        assertNull(d.parentSpanId());
    }

    @Test
    @DisplayName("只有合法自定义头时行为与旧版完全一致（source=upstream，无父 span）")
    void customOnlyUnchanged() {
        TracePropagator.InboundDecision d = propagator.decide(CUSTOM_ID, null);
        log.info("[输入] X-Trace-Id={} 无 traceparent | [依据] 旧行为不变 => upstream 沿用", CUSTOM_ID);
        assertEquals(TracePropagator.Source.UPSTREAM, d.source());
        assertEquals(CUSTOM_ID, d.traceId());
        assertNull(d.parentSpanId());
    }
}
