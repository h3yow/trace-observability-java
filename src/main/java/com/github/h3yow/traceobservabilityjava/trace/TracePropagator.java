package com.github.h3yow.traceobservabilityjava.trace;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 负责解析上游追踪标识、合法性校验以及新标识的生成。
 *
 * <p>规则：上游提供的 traceId 匹配可配置正则时沿用（保持同一条链路）；
 * 缺失或非法时生成新的 traceId，避免脏标识进入系统。
 *
 * <p>同时兼容 W3C Trace Context（{@code traceparent} 头）。两种来源的取舍规则
 * （明确且稳定，与到达顺序无关）：
 * <ol>
 *   <li>{@code traceparent} <b>完全合法（仅小写 hex）</b> → 采用其中的 traceId（来源 {@link Source#W3C}），
 *       其中的 parent-id 作为本服务根 span 的父 span；</li>
 *   <li>否则自定义头（默认 {@code X-Trace-Id}）合法 → 沿用（来源 {@link Source#UPSTREAM}）；</li>
 *   <li>两者都缺失/非法（含全零等不可信值，以及 traceparent 任意一段含大写字母）→ 重新生成（来源 {@link Source#GENERATED}）。</li>
 * </ol>
 * 一次请求只产出一套标识，日志、指标与响应回写的自定义头/traceparent 共用同一个 traceId。
 * 非法标准头（如大写写法）中的 traceId 不会被沿用、归一化或写入日志/指标/响应头。
 */
public final class TracePropagator {

    /** 追踪标识来源。 */
    public enum Source { W3C, UPSTREAM, GENERATED }

    /** 一次请求的标识取舍结果（不可变）。 */
    public record InboundDecision(String traceId, String parentSpanId, Source source,
                                  String inboundCustom, String inboundTraceparent) {}

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

    /**
     * 在两种来源之间做唯一取舍并创建根上下文。
     *
     * @param inboundCustom      自定义头（如 X-Trace-Id）的原始值，可能为 null
     * @param inboundTraceparent W3C traceparent 头的原始值，可能为 null
     */
    public InboundDecision decide(String inboundCustom, String inboundTraceparent) {
        Traceparent tp = Traceparent.parse(inboundTraceparent);
        if (tp != null) {
            return new InboundDecision(tp.traceId(), tp.parentSpanId(), Source.W3C,
                    inboundCustom, inboundTraceparent);
        }
        if (isValid(inboundCustom)) {
            return new InboundDecision(inboundCustom.trim().toLowerCase(Locale.ROOT), null,
                    Source.UPSTREAM, inboundCustom, inboundTraceparent);
        }
        return new InboundDecision(generateTraceId(), null, Source.GENERATED,
                inboundCustom, inboundTraceparent);
    }

    /** 依据取舍结果创建根上下文（父 span 仅当来源为 W3C 时存在）。 */
    public TraceContext createRootContextFor(InboundDecision decision) {
        String requestId = randomHex(16);
        return new TraceContext(decision.traceId(), generateSpanId(),
                decision.parentSpanId(), requestId);
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
