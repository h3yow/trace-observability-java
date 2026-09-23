package com.github.h3yow.traceobservabilityjava.trace;

/**
 * 不可变的追踪上下文。在请求入口创建，并在向线程池提交任务时被快照复制。
 */
public final class TraceContext {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String requestId;

    public TraceContext(String traceId, String spanId, String parentSpanId, String requestId) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.requestId = requestId;
    }

    public String traceId() { return traceId; }
    public String spanId() { return spanId; }
    public String parentSpanId() { return parentSpanId; }
    public String requestId() { return requestId; }

    /** 生成一个以当前 span 为父的新子上下文（新 spanId，traceId/requestId 不变）。 */
    public TraceContext withNewChildSpan(String childSpanId) {
        return new TraceContext(traceId, childSpanId, spanId, requestId);
    }
}
