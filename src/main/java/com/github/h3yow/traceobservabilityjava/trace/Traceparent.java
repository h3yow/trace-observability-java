package com.github.h3yow.traceobservabilityjava.trace;

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
 *
 * <p>大小写口径严格对齐 W3C Trace Context：各段只允许<b>小写</b>十六进制
 * （{@code 0-9a-f}）。任意一段、任意一位出现大写字母（整段大写或大小写混合）
 * 都属于格式不合法，与缺字段、全零一样按无效处理，调用方必须回退/重新生成，
 * 不得沿用其中的 trace-id。因此本类不做大小写归一化：合法输入本身即为小写。
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
        String version = parts[0];
        String traceId = parts[1];
        String parentId = parts[2];
        String flags = parts[3];

        // 严格按 W3C ABNF 校验：只接受小写 hex，任何大写/混合写法直接判无效，
        // 不做 toLowerCase 归一化，避免把不合规的 trace-id 当可信值沿用。
        if (!isLowerHex(version, 2) || "ff".equals(version)) {
            return null;
        }
        if (!isLowerHex(traceId, 32) || isAllZeros(traceId)) {
            return null;
        }
        if (!isLowerHex(parentId, 16) || isAllZeros(parentId)) {
            return null;
        }
        if (!isLowerHex(flags, 2)) {
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

    /**
     * 是否为恰好 {@code len} 位的<b>小写</b>十六进制字符串。
     * W3C traceparent 的 ABNF 明确使用 lowercase {@code HEXDIGLC}，
     * 大写字母（A-F）不属于合法字符，这里不做大小写兼容。
     */
    private static boolean isLowerHex(String s, int len) {
        if (s == null || s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) {
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
