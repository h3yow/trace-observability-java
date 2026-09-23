package com.github.h3yow.traceobservabilityjava.trace;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 负责解析上游追踪标识、合法性校验以及新标识的生成。
 *
 * <p>规则：上游提供的 traceId 匹配可配置正则时沿用（保持同一条链路）；
 * 缺失或非法时生成新的 traceId，避免脏标识进入系统。
 */
public final class TracePropagator {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final String headerName;
    private final Pattern validPattern;
    private final int idLength;
    private final SecureRandom random = new SecureRandom();

    public TracePropagator(String headerName, String validPattern, int idLength) {
        this.headerName = headerName;
        this.validPattern = Pattern.compile(validPattern);
        // 保证生成长度本身合法：夹在 16~32（默认正则允许的区间）
        this.idLength = Math.max(16, Math.min(32, idLength));
    }

    /** 判断上游提供的 traceId 是否合法；null/空白/格式错均视为非法。 */
    public boolean isValid(String traceId) {
        if (traceId == null) {
            return false;
        }
        String trimmed = traceId.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        return validPattern.matcher(trimmed).matches();
    }

    /** 生成一个全新的合法 traceId（小写十六进制）。 */
    public String generateTraceId() {
        return randomHex(idLength);
    }

    /** 生成一个 16 位 spanId。 */
    public String generateSpanId() {
        return randomHex(16);
    }

    /**
     * 为请求创建根上下文：
     * 入站 traceId 合法则沿用（来源 upstream），否则生成新的（来源 generated）。
     */
    public TraceContext createRootContext(String inboundTraceId) {
        String traceId = isValid(inboundTraceId) ? inboundTraceId.trim().toLowerCase(Locale.ROOT)
                : generateTraceId();
        String requestId = randomHex(16);
        return new TraceContext(traceId, generateSpanId(), null, requestId);
    }

    /** 返回从请求头读取 traceId 所用的 header 名称。 */
    public String headerName() {
        return headerName;
    }

    private String randomHex(int length) {
        byte[] bytes = new byte[(length + 1) / 2];
        random.nextBytes(bytes);
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            int b = bytes[i / 2];
            int nibble = (i % 2 == 0) ? (b >> 4) & 0x0F : b & 0x0F;
            out[i] = HEX[nibble];
        }
        return new String(out);
    }
}
