package com.github.h3yow.traceobservabilityjava.trace;

/**
 * W3C Trace Context {@code traceparent} 请求头的解析与校验（零依赖、纯函数）。
 *
 * <p>格式：{@code version-traceid-spanid-flags}，例如
 * {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01}。
 *
 * <p>按规范严格校验，以下一律视为【无效】（调用方应回退到自定义头或重新生成）：
 * <ul>
 *   <li>缺字段 / 多字段 / 空值；</li>
 *   <li>version 不是 2 位小写十六进制，或为保留值 {@code ff}；</li>
 *   <li>trace-id 不是 32 位小写十六进制，或 <b>全零</b>（规范明令不可信）；</li>
 *   <li>span-id 不是 16 位小写十六进制，或全零；</li>
 *   <li>flags 不是 2 位小写十六进制；</li>
 *   <li>出现大写字母（规范要求小写，大写即非法）。</li>
 * </ul>
 */
public final class W3cTraceparent {

    /** 标准请求/响应头名。 */
    public static final String HEADER = "traceparent";

    private static final String VERSION_00 = "00";
    private static final String ALL_ZERO_16 = "0000000000000000";
    private static final String ALL_ZERO_32 = "00000000000000000000000000000000";

    private W3cTraceparent() {}

    /** 解析结果：上游 trace-id 与父 span-id（均为校验通过的合法值）。 */
    public record Parsed(String traceId, String parentSpanId, int flags) {}

    /**
     * 解析并校验 traceparent 头。
     *
     * @param headerValue 原始头值，可为 null
     * @return 合法时返回 {@link Parsed}；任何不合法情况返回 {@code null}
     */
    public static Parsed parse(String headerValue) {
        if (headerValue == null) {
            return null;
        }
        String value = headerValue.trim();
        String[] parts = value.split("-", -1);
        if (parts.length != 4) {
            return null;
        }
        String version = parts[0];
        String traceId = parts[1];
        String spanId = parts[2];
        String flags = parts[3];

        if (!isLowerHex(version, 2) || "ff".equals(version)) {
            return null;
        }
        // version 00 不允许 trace-id 段之后再出现额外字段（split 长度已保证恰好 4 段）
        if (!isLowerHex(traceId, 32) || ALL_ZERO_32.equals(traceId)) {
            return null;
        }
        if (!isLowerHex(spanId, 16) || ALL_ZERO_16.equals(spanId)) {
            return null;
        }
        if (!isLowerHex(flags, 2)) {
            return null;
        }
        return new Parsed(traceId, spanId, Integer.parseInt(flags, 16));
    }

    /**
     * 生成回写给上游的 traceparent 值：采用最终实际生效的 traceId 与当前 spanId，
     * 固定 version=00、flags=01（sampled），方便上游按标准格式关联。
     */
    public static String format(String traceId, String spanId) {
        return VERSION_00 + "-" + traceId + "-" + spanId + "-01";
    }

    private static boolean isLowerHex(String s, int expectedLength) {
        if (s == null || s.length() != expectedLength) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
