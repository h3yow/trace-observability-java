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
    @DisplayName("任意一段出现大写 hex（整段大写/一位大写/大小写混合）一律按 W3C 非法拒绝")
    void rejectsUppercaseOrMixedCaseHex() {
        // 覆盖 version / trace-id / parent-id / flags 四段：整段大写、仅一位大写、大小写混合
        String[] invalid = {
                // version 段：一位大写、全大写
                "0A-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01",
                "FF-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-01",
                // trace-id 段：整段大写、仅第二位字母大写、中间一位大写、末位字母大写
                "00-" + "4BF92F3577B34DA6A3CE929D0E0E4736" + "-" + VALID_SPAN_ID + "-01",
                "00-4Bf92f3577b34da6a3ce929d0e0e4736-" + VALID_SPAN_ID + "-01",
                "00-4bF92f3577b34da6a3ce929d0e0e4736-" + VALID_SPAN_ID + "-01",
                "00-4bf92f3577b34da6a3ce929d0e0e473A-" + VALID_SPAN_ID + "-01",
                // parent-id 段：整段大写、混合
                "00-" + VALID_TRACE_ID + "-00F067AA0BA902B7-01",
                "00-" + VALID_TRACE_ID + "-00f067aA0ba902b7-01",
                // flags 段：大写
                "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-0F",
                "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-1A",
                // 整串全大写
                VALID_TP.toUpperCase(java.util.Locale.ROOT),
        };
        for (String raw : invalid) {
            Traceparent tp = Traceparent.parse(raw);
            log.info("[输入] traceparent='{}' | [依据] W3C 只允许小写 hex，存在大写/混合字符 => 期望 null，实际={}",
                    raw, tp == null ? "null(无效)" : "误判为合法:" + tp);
            assertNull(tp, "含大写字母的 traceparent 必须无效: " + raw);
        }
    }

    @Test
    @DisplayName("严格小写但其余结构正确的 traceparent 不受影响（回归保护）")
    void lowercaseStillAccepted() {
        // 合法值里本来就有 a-f 小写字母，确保严格口径没有误伤
        Traceparent tp = Traceparent.parse(VALID_TP);
        log.info("[输入] traceparent='{}' | [依据] 全部小写且结构合法 => 必须解析成功", VALID_TP);
        assertNotNull(tp);
        assertEquals(VALID_TRACE_ID, tp.traceId());
        // 另一个含 a-f 的典型合法值
        String another = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-00";
        Traceparent parsed = Traceparent.parse(another);
        log.info("[输入] traceparent='{}' | [依据] 全部小写 flags=00 => 解析成功且 sampled=false", another);
        assertNotNull(parsed);
        assertFalse(parsed.sampled(), "flags=00 未置采样位");
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
    @DisplayName("取舍规则：traceparent 仅因大写非法时同样回退到合法自定义头，且不沿用其大写 trace-id")
    void fallsBackToCustomWhenTraceparentUppercase() {
        // trace-id 整段大写；其余结构本身没问题，唯一非法原因就是大小写
        String uppercaseTp = "00-" + VALID_TRACE_ID.toUpperCase(java.util.Locale.ROOT)
                + "-" + VALID_SPAN_ID + "-01";
        TracePropagator.InboundDecision d = propagator.decide(CUSTOM_ID, uppercaseTp);
        log.info("[输入] X-Trace-Id={} + traceparent='{}'(trace-id 大写) | "
                + "[依据] W3C 大写即非法 => 回退自定义头；实际 source={} traceId={}",
                CUSTOM_ID, uppercaseTp, d.source(), d.traceId());
        assertEquals(TracePropagator.Source.UPSTREAM, d.source());
        assertEquals(CUSTOM_ID, d.traceId(), "不得沿用大写 traceparent 中的 trace-id");
        assertNotEquals(VALID_TRACE_ID.toUpperCase(java.util.Locale.ROOT), d.traceId());
        assertNull(d.parentSpanId(), "非法 traceparent 的 parent-id 同样不得采用");
    }

    @Test
    @DisplayName("取舍规则：大写非法 traceparent + 非法自定义头 => 重新生成，结果与两段脏输入都不同")
    void regeneratesWhenUppercaseTraceparentAndBadCustom() {
        String uppercaseTp = "00-" + VALID_TRACE_ID.toUpperCase(java.util.Locale.ROOT)
                + "-" + VALID_SPAN_ID.toUpperCase(java.util.Locale.ROOT) + "-01";
        TracePropagator.InboundDecision d = propagator.decide("evil-id!!", uppercaseTp);
        log.info("[输入] X-Trace-Id='evil-id!!' + traceparent='{}'(全大写 id) | "
                + "[依据] 均不可用 => 重新生成小写 hex；实际 traceId={}", uppercaseTp, d.traceId());
        assertEquals(TracePropagator.Source.GENERATED, d.source());
        assertTrue(d.traceId().matches("^[0-9a-f]{32}$"));
        assertNotEquals(VALID_TRACE_ID.toUpperCase(java.util.Locale.ROOT), d.traceId());
        assertNotEquals(VALID_TRACE_ID, d.traceId(), "也不得先归一化再沿用");
        assertNull(d.parentSpanId());
    }

    @Test
    @DisplayName("取舍规则：仅大写非法 traceparent、无自定义头 => 重新生成")
    void regeneratesWhenUppercaseTraceparentOnly() {
        String mixedTp = "00-" + VALID_TRACE_ID + "-" + VALID_SPAN_ID + "-0F";
        TracePropagator.InboundDecision d = propagator.decide(null, mixedTp);
        log.info("[输入] 无自定义头 + traceparent='{}'(flags 大写) | [依据] W3C 非法 => 重新生成；实际={}",
                mixedTp, d.traceId());
        assertEquals(TracePropagator.Source.GENERATED, d.source());
        assertTrue(d.traceId().matches("^[0-9a-f]{32}$"));
        assertNotEquals(VALID_TRACE_ID, d.traceId());
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
