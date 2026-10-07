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
 *   <li>version 为 2 位<b>小写</b>十六进制且不得为 "ff"（ff 为规范保留的非法值）；</li>
 *   <li>traceId 为 32 位<b>小写</b>十六进制，且不得全零（全零为规范定义的不可信值）；</li>
 *   <li>parentId(spanId) 为 16 位<b>小写</b>十六进制，且不得全零；</li>
 *   <li>flags 为 2 位<b>小写</b>十六进制。</li>
 * </ul>
 *
 * <p><b>大小写口径（W3C Trace Context）：</b>header 值中的十六进制字母只允许小写
 * {@code [0-9a-f]}。任意一段（version/trace-id/parent-id/flags）中出现大写字母
 * {@code A-F}——无论整段大写、仅一位大写还是大小写混写——一律按格式非法处理，
 * 与缺字段、全零等同：其中的 traceId 不可信、不得沿用，调用方必须回退/重新生成。
 * 本解析器不做大小写归一化（不会先 toLowerCase 再校验）。
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
        // 关键：直接按原始字符严格校验，绝不能先 toLowerCase 再判 hex——
        // W3C 规定 hex 字母只允许小写，任意一段出现 A-F（整段大写/一位大写/混写）即非法。
        String version = parts[0];
        String traceId = parts[1];
        String parentId = parts[2];
        String flags = parts[3];

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

    /** 严格小写十六进制校验：长度必须匹配，且每一位只能是 0-9 / a-f（A-F 一律拒绝）。 */
    private static boolean isLowerHex(String s, int len) {
        if (s == null || s.length() != len) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
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
