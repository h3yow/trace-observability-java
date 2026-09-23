package com.github.h3yow.traceobservabilityjava.observability;

import com.github.h3yow.traceobservabilityjava.trace.TraceContext;

import java.util.List;

/**
 * 进程内观察结果存储：每条 traceId 仅 start 一次、finalize 一次，
 * 事件可追加，最终归类原子提交且先到结论不可被覆盖。
 */
public interface ObservationStore {

    void start(String traceId, String method, String path, boolean upstreamProvided);

    void event(SpanEvent event);

    void event(TraceContext context, String operation, int attempt,
               OutcomeCategory category, long elapsedNanos, Throwable error, String message);

    /**
     * 原子结案：仅第一次调用生效，返回是否由本次调用完成归类。
     */
    boolean finalize(String traceId, int status, OutcomeCategory category,
                     long elapsedNanos, String errorType);

    RequestObservation get(String traceId);

    List<RequestObservation> recent(int limit);

    MetricsSnapshot snapshot();

    void reset();
}
