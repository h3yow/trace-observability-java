package com.github.h3yow.traceobservabilityjava.trace;

import java.util.Locale;

/**
 * W3C Trace Context 的 traceparent 头解析与校验（不可变值对象）。
 *
 * <p>格式：{@code version-traceId-parentId-flags}，例如
 * {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}。
 *
 * <p>校验规则（任一不满足即视为无效，调用方应重新生成标识）：
 * <ul>
 *   <li>必须恰好 4 段，以 '-' 分隔；</li>
 *   <li>version 为 2 位十六进制且不得为 "ff"（ff 为规范保留的非法值）；</li>
 *   <li>traceId 为 32 位十六进制，且不得全零（全零为规范定义的不可信值）；</li>
 *   <li>parentId(spanId) 为 16 位十六进制，且不得全零；</li>
 *   <li>flags 为 2 位十六进制。</li>
 * </ul>
 * 大小写不敏感，解析后统一归一为小写。
 */
public final class Traceparent {

    public static final String HEADER = "traceparent";

    private final String version;
    private final String traceId;
    private final String parentSpanId;
    private final String flags;

    private Traceparent(String version, String traceId, String parentSpanId, String flags) {
        this.version = version;
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.flags = flags;
    }

    /** 解析并校验；非法输入一律返回 null（不抛异常，解析失败等价于"没带标准头"）。 */
    public static Traceparent parse(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        String[] parts = trimmed.split("-", -1);
        if (parts.length != 4) {
            return null;
        }
        String version = parts[0].toLowerCase(Locale.ROOT);
        String traceId = parts[1].toLowerCase(Locale.ROOT);
        String parentId = parts[2].toLowerCase(Locale.ROOT);
        String flags = parts[3].toLowerCase(Locale.ROOT);

        if (!isHex(version, 2) || "ff".equals(version)) {
            return null;
        }
        if (!isHex(traceId, 32) || isAllZeros(traceId)) {
            return null;
        }
        if (!isHex(parentId, 16) || isAllZeros(parentId)) {
            return null;
        }
        if (!isHex(flags, 2)) {
            return null;
        }
        return new Traceparent(version, traceId, parentId, flags);
    }

    public String version() { return version; }
    public String traceId() { return traceId; }
    public String parentSpanId() { return parentSpanId; }
    public String flags() { return flags; }

    /** 是否设置了采样位（flags 最低位）。 */
    public boolean sampled() {
        return (Character.digit(flags.charAt(1), 16) & 0x01) == 0x01;
    }

    /** 用给定 traceId/spanId 组装一个 version=00、flags=01 的标准头值。 */
    public static String format(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static boolean isHex(String s, int len) {
        if (s == null || s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllZeros(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != '0') {
                return false;
            }
        }
        return true;
    }
}
