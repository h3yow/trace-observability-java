package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.trace.TraceContext;
import com.github.h3yow.traceobservabilityjava.trace.TracePropagator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上游标识：合法沿用，缺失/非法生成新的。输入与判定依据均打印。
 */
class TracePropagatorTest {

    private static final Logger log = LoggerFactory.getLogger(TracePropagatorTest.class);
    private final TracePropagator propagator =
            new TracePropagator("X-Trace-Id", "^[0-9a-fA-F]{16,32}$", 32);
    private final Pattern expectedPattern = Pattern.compile("^[0-9a-f]{16,32}$");

    @Test
    @DisplayName("合法上游 traceId 必须被沿用")
    void reusesValidInboundTraceId() {
        String inbound = "abcdef0123456789abcdef0123456789";
        TraceContext ctx = propagator.createRootContext(inbound);
        log.info("[输入] inboundTraceId={} | [依据] 匹配 16~32 位十六进制 => 期望沿用", inbound);
        assertEquals(inbound, ctx.traceId());
        assertNotNull(ctx.spanId());
        assertNotNull(ctx.requestId());
        assertNull(ctx.parentSpanId());
    }

    @Test
    @DisplayName("缺失的 traceId 必须生成新的")
    void generatesWhenMissing() {
        TraceContext ctx = propagator.createRootContext(null);
        log.info("[输入] inboundTraceId=null | [依据] 为 null => isInvalid，期望生成 32 位小写 hex");
        assertTrue(expectedPattern.matcher(ctx.traceId()).matches(),
                "生成的 traceId 必须合法，实际=" + ctx.traceId());
    }

    @Test
    @DisplayName("非法 traceId（注入/长度错误/非hex）必须被拒绝并重新生成")
    void regeneratesWhenInvalid() {
        String[] invalid = {"", "   ", "short", "zzzz-not-hex-zzzz", "ab cd ef 12 34 56",
                "<script>x</script>", "0123456789abcdef0123456789abcdef_TO_LONG_"};
        for (String inbound : invalid) {
            TraceContext ctx = propagator.createRootContext(inbound);
            log.info("[输入] inboundTraceId='{}' | [依据] 不匹配正则 => 期望新生成，实际={}",
                    inbound, ctx.traceId());
            assertTrue(expectedPattern.matcher(ctx.traceId()).matches());
            assertNotEquals(inbound == null ? null : inbound.trim(), ctx.traceId());
        }
    }

    @Test
    @DisplayName("子 span 必须保持同一 traceId 且父子 span 关联")
    void childSpanKeepsTraceId() {
        TraceContext root = propagator.createRootContext("fedcba9876543210fedcba9876543210");
        TraceContext child = root.withNewChildSpan(propagator.generateSpanId());
        log.info("[输入] root={} spanId={} | [依据] 子上下文 traceId 不变、parentSpanId=root.spanId",
                root.traceId(), root.spanId());
        assertEquals(root.traceId(), child.traceId());
        assertEquals(root.spanId(), child.parentSpanId());
        assertNotEquals(root.spanId(), child.spanId());
    }
}
