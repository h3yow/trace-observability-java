package com.github.h3yow.traceobservabilityjava.trace;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 追踪标识的合法性判定与生成。
 *
 * 传播规则：
 * <ol>
 *   <li>优先读取 {@code X-Trace-Id}（32 位小写十六进制）；</li>
 *   <li>缺失时读取 W3C {@code traceparent}（version-traceId-spanId-flags）；</li>
 *   <li>两者缺失或格式非法（含全 0 标识）时，生成新标识。</li>
 * </ol>
 * 标识生成基于 {@link SecureRandom}，按字节转十六进制，无阻塞、无需网络。
 */
public final class TraceIds {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String TRACE_PARENT_HEADER = "traceparent";

    private static final int TRACE_ID_HEX_LENGTH = 32;
    private static final int SPAN_ID_HEX_LENGTH = 16;
    private static final String ALL_ZERO_TRACE_ID = "0".repeat(TRACE_ID_HEX_LENGTH);
    private static final String ALL_ZERO_SPAN_ID = "0".repeat(SPAN_ID_HEX_LENGTH);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private TraceIds() {
    }

    /** 校验 32 位十六进制 traceId，非空、长度正确、非全 0。 */
    public static boolean isValidTraceId(String traceId) {
        return traceId != null
                && traceId.length() == TRACE_ID_HEX_LENGTH
                && isLowerHex(traceId)
                && !ALL_ZERO_TRACE_ID.equals(traceId);
    }

    /** 校验 16 位十六进制 spanId。 */
    public static boolean isValidSpanId(String spanId) {
        return spanId != null
                && spanId.length() == SPAN_ID_HEX_LENGTH
                && isLowerHex(spanId)
                && !ALL_ZERO_SPAN_ID.equals(spanId);
    }

    /**
     * 从请求头解析上游 traceId。
     *
     * @return 合法的上游 traceId；缺失或非法时返回 null（由调用方生成新标识）
     */
    public static String extractUpstreamTraceId(String traceIdHeader, String traceParentHeader) {
        if (traceIdHeader != null) {
            String candidate = traceIdHeader.trim().toLowerCase();
            if (isValidTraceId(candidate)) {
                return candidate;
            }
        }
        if (traceParentHeader != null) {
            String fromW3c = parseW3CTraceParent(traceParentHeader.trim());
            if (fromW3c != null) {
                return fromW3c;
            }
        }
        return null;
    }

    public static String newTraceId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public static String newSpanId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    /** 上游标识合法则沿用，否则生成新标识；返回值保证合法。 */
    public static String resolveOrGenerate(String upstreamTraceId) {
        return isValidTraceId(upstreamTraceId) ? upstreamTraceId : newTraceId();
    }

    private static boolean isLowerHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean lower = c >= 'a' && c <= 'f';
            if (!digit && !lower) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析 W3C traceparent，形如 {@code 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01}。
     * 非法版本或格式返回 null。
     */
    private static String parseW3CTraceParent(String traceParent) {
        String[] parts = traceParent.split("-");
        if (parts.length != 4) {
            return null;
        }
        String version = parts[0];
        String traceId = parts[1];
        if (version.length() != 2 || "ff".equals(version)) {
            return null;
        }
        return isValidTraceId(traceId) ? traceId : null;
    }
}
