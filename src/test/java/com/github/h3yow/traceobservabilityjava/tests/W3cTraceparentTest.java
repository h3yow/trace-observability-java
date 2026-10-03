package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.trace.W3cTraceparent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3C traceparent 解析：合法接受；缺字段/格式错/全零/大写/保留版本一律拒绝。
 */
class W3cTraceparentTest {

    private static final Logger log = LoggerFactory.getLogger(W3cTraceparentTest.class);

    private static final String VALID =
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    @DisplayName("合法 traceparent 解析出 trace-id 与父 span-id")
    void parsesValid() {
        W3cTraceparent.Parsed parsed = W3cTraceparent.parse(VALID);
        log.info("[输入] traceparent={} | [依据] 4 段合法小写 hex => 期望解析成功", VALID);
        assertNotNull(parsed);
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", parsed.traceId());
        assertEquals("00f067aa0ba902b7", parsed.parentSpanId());
        assertEquals(1, parsed.flags());
    }

    @Test
    @DisplayName("null / 空 / 缺字段 / 多字段 => 无效")
    void rejectsMissingFields() {
        String[] invalid = {
                null, "", "   ",
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7",          // 缺 flags
                "00-4bf92f3577b34da6a3ce929d0e0e4736",                            // 缺 span-id+flags
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01-extra",  // 多字段
                "00--00f067aa0ba902b7-01",                                        // 空 trace-id
        };
        for (String v : invalid) {
            W3cTraceparent.Parsed parsed = W3cTraceparent.parse(v);
            log.info("[输入] traceparent='{}' | [依据] 字段数/空值不符 => 期望 null，实际={}",
                    v, parsed);
            assertNull(parsed, "应判为无效: " + v);
        }
    }

    @Test
    @DisplayName("全零 trace-id / 全零 span-id => 不可信，判无效")
    void rejectsAllZero() {
        String zeroTrace = "00-00000000000000000000000000000000-00f067aa0ba902b7-01";
        String zeroSpan = "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01";
        log.info("[输入] trace-id 全零 | [依据] W3C 规范明令全零不可信 => 期望 null");
        assertNull(W3cTraceparent.parse(zeroTrace));
        log.info("[输入] span-id 全零 | [依据] 同上 => 期望 null");
        assertNull(W3cTraceparent.parse(zeroSpan));
    }

    @Test
    @DisplayName("格式错误（大写/非hex/长度错/保留版本ff）=> 无效")
    void rejectsMalformed() {
        String[] invalid = {
                "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",  // 大写 trace-id
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00F067AA0BA902B7-01",  // 大写 span-id
                "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",  // 保留版本 ff
                "0-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",   // version 长度错
                "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",   // trace-id 31 位
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b-01",   // span-id 15 位
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",   // flags 1 位
                "00-4bf92f3577b34da6a3ce929d0e0e47zz-00f067aa0ba902b7-01",  // 非 hex
        };
        for (String v : invalid) {
            W3cTraceparent.Parsed parsed = W3cTraceparent.parse(v);
            log.info("[输入] traceparent='{}' | [依据] 违反 W3C 格式 => 期望 null，实际={}", v, parsed);
            assertNull(parsed, "应判为无效: " + v);
        }
    }

    @Test
    @DisplayName("回写格式：00-<traceId>-<spanId>-01")
    void formatsForResponse() {
        String formatted = W3cTraceparent.format(
                "4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");
        log.info("[输入] traceId/spanId 合法值 | [依据] 回写必须仍是合法 traceparent => {}", formatted);
        assertEquals(VALID, formatted);
        assertNotNull(W3cTraceparent.parse(formatted), "回写值必须能再次被解析");
    }
}
