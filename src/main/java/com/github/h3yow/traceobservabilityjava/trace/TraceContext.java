package com.github.h3yow.traceobservabilityjava.trace;

/**
 * 不可变的追踪上下文：一次逻辑请求一条 traceId，每次进入方法/跨线程产生新的 spanId。
 *
 * @param traceId          整条链路唯一标识（16 字节十六进制，32 字符）
 * @param spanId           当前执行单元标识（8 字节十六进制，16 字符）
 * @param parentSpanId     父执行单元标识，根 span 为 null
 * @param upstreamProvided traceId 是否由上游提供（false 表示本服务生成）
 * @param createdAtMillis  上下文创建时间
 */
public record TraceContext(String traceId,
                           String spanId,
                           String parentSpanId,
                           boolean upstreamProvided,
                           long createdAtMillis) {

    public TraceContext {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        if (spanId == null || spanId.isBlank()) {
            throw new IllegalArgumentException("spanId must not be blank");
        }
    }

    /** 构造请求入口的根 span 上下文。 */
    public static TraceContext root(String traceId, String spanId, boolean upstreamProvided) {
        return new TraceContext(traceId, spanId, null, upstreamProvided, System.currentTimeMillis());
    }

    /** 派生子 span：traceId 与来源标记保持不变，父 span 指向当前 span。 */
    public TraceContext withChild(String childSpanId) {
        return new TraceContext(traceId, childSpanId, this.spanId, upstreamProvided,
                System.currentTimeMillis());
    }
}
