package com.github.h3yow.traceobservabilityjava.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 追踪标识解析与生成的单元测试。
 * 每个用例打印输入与判定依据，便于从测试日志核对口径。
 */
class TraceIdsTest {

    private static void logCase(String title, String input, String basis, Object actual) {
        System.out.printf("[TraceIdsTest] %s | 输入=%s | 判定依据=%s | 实际=%s%n",
                title, input, basis, actual);
    }

    @Test
    @DisplayName("合法 32 位十六进制 traceId 必须沿用")
    void reuseValidTraceId() {
        String upstream = "0af7651916cd43dd8448eb211c80319c";
        String resolved = TraceIds.resolveOrGenerate(upstream);
        logCase("沿用合法标识", upstream, "长度32且为小写十六进制且非全0", resolved);
        assertEquals(upstream, resolved);
        assertTrue(TraceIds.isValidTraceId(resolved));
    }

    @Test
    @DisplayName("缺失标识时必须生成新的合法标识")
    void generateWhenMissing() {
        String resolved = TraceIds.resolveOrGenerate(null);
        logCase("缺失标识", "null", "上游为空 → SecureRandom 生成 16 字节", resolved);
        assertNotNull(resolved);
        assertTrue(TraceIds.isValidTraceId(resolved), "生成的标识必须自身合法");

        String blank = TraceIds.resolveOrGenerate("   ");
        logCase("空白标识", "'   '", "trim 后长度为 0 → 生成新标识", blank);
        assertTrue(TraceIds.isValidTraceId(blank));
    }

    @Test
    @DisplayName("各类非法标识必须被拒绝并重新生成")
    void rejectIllegalTraceIds() {
        String[] illegal = {
                "xyz7651916cd43dd8448eb211c80319c",          // 含非法字符
                "0af76519",                                   // 长度不足
                "0af7651916cd43dd8448eb211c80319c00",        // 长度过长
                "00000000000000000000000000000000",          // 全 0（W3C 明确非法）
                "0AF7651916CD43DD8448EB211C80319C",          // 大写
        };
        for (String bad : illegal) {
            assertFalse(TraceIds.isValidTraceId(bad), "非法输入必须校验失败: " + bad);
            String resolved = TraceIds.resolveOrGenerate(bad);
            logCase("拒绝非法标识", bad, "不满足 32 位小写十六进制或为全 0", resolved);
            assertNotEquals(bad, resolved, "非法标识不得被沿用");
            assertTrue(TraceIds.isValidTraceId(resolved));
        }
    }

    @Test
    @DisplayName("X-Trace-Id 与 W3C traceparent 的解析优先级与合法性")
    void extractHeaders() {
        String direct = "11111111111111111111111111111111";
        String w3c = "00-22222222222222222222222222222222-b7ad6b7169203331-01";

        String preferDirect = TraceIds.extractUpstreamTraceId(direct, w3c);
        logCase("X-Trace-Id 优先", "X-Trace-Id + traceparent 同时存在",
                "显式头优先", preferDirect);
        assertEquals(direct, preferDirect);

        String fromW3c = TraceIds.extractUpstreamTraceId(null, w3c);
        logCase("回退 traceparent", w3c, "解析第 2 段 trace-id", fromW3c);
        assertEquals("22222222222222222222222222222222", fromW3c);

        String badW3c = "00-00000000000000000000000000000000-b7ad6b7169203331-01";
        String rejected = TraceIds.extractUpstreamTraceId(null, badW3c);
        logCase("拒绝非法 traceparent", badW3c, "trace-id 全 0 → 返回 null", rejected);
        assertNull(rejected);

        String badVersion = "ff-22222222222222222222222222222222-b7ad6b7169203331-01";
        logCase("拒绝非法版本号", badVersion, "version=ff 非法",
                TraceIds.extractUpstreamTraceId(null, badVersion));
        assertNull(TraceIds.extractUpstreamTraceId(null, badVersion));
    }

    @Test
    @DisplayName("生成的标识不得冲突（抽样 10000 次）")
    void generatedIdsAreUnique() {
        java.util.Set<String> traceIds = new java.util.HashSet<>();
        java.util.Set<String> spanIds = new java.util.HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            String traceId = TraceIds.newTraceId();
            String spanId = TraceIds.newSpanId();
            assertTrue(traceIds.add(traceId), "traceId 出现碰撞");
            assertTrue(spanIds.add(spanId), "spanId 出现碰撞");
            assertTrue(TraceIds.isValidSpanId(spanId));
        }
        assertEquals(10_000, traceIds.size());
        assertEquals(10_000, spanIds.size());
    }
}
