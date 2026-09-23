package com.github.h3yow.traceobservabilityjava.trace;

import com.github.h3yow.traceobservabilityjava.observation.ObservationContext;
import com.github.h3yow.traceobservabilityjava.observation.ObservationHolder;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * 提交任务那一刻对线程上下文的不可变快照：
 * 包含 TraceContext、ObservationContext 以及 MDC（结构化日志关联用）。
 *
 * <p>在工作线程上 {@link #restore()} 恢复，并在任务结束时 {@link #cleanup()} 还原为
 * 工作线程之前的状态，杜绝线程池复用把上一个请求的上下文带给下一个任务。
 */
final class ContextSnapshot {

    private final TraceContext traceContext;
    private final ObservationContext observationContext;
    private final Map<String, String> mdc;

    private ContextSnapshot(TraceContext traceContext,
                            ObservationContext observationContext,
                            Map<String, String> mdc) {
        this.traceContext = traceContext;
        this.observationContext = observationContext;
        this.mdc = mdc;
    }

    /** 在【提交方线程】捕获当前上下文快照。 */
    static ContextSnapshot capture() {
        Map<String, String> mdcCopy = MDC.getCopyOfContextMap();
        return new ContextSnapshot(
                TraceContextHolder.get(),
                ObservationHolder.get(),
                mdcCopy == null ? Map.of() : new HashMap<>(mdcCopy));
    }

    /** 在【工作线程】恢复快照，返回之前的现场以便结束后还原。 */
    @SuppressWarnings("unchecked")
    RestoreToken restore() {
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        TraceContext previousTrace = TraceContextHolder.get();
        ObservationContext previousObs = ObservationHolder.get();

        // 先彻底清空，再写入快照，避免把工作线程残留值保留下来
        MDC.clear();
        TraceContextHolder.clear();
        ObservationHolder.clear();

        if (traceContext != null) {
            TraceContextHolder.set(traceContext);
            MDC.put("traceId", traceContext.traceId());
            MDC.put("spanId", traceContext.spanId());
            MDC.put("requestId", traceContext.requestId());
        }
        if (observationContext != null) {
            ObservationHolder.set(observationContext);
        }
        for (Map.Entry<String, String> e : mdc.entrySet()) {
            MDC.put(e.getKey(), e.getValue());
        }
        return new RestoreToken(previousTrace, previousObs, previousMdc);
    }

    /** 把任务包成：恢复快照 -> 执行 -> 无论成功失败还原现场。 */
    Runnable wrap(Runnable task) {
        ContextSnapshot snapshot = this;
        return () -> {
            RestoreToken token = snapshot.restore();
            try {
                task.run();
            } finally {
                token.restorePrevious();
            }
        };
    }

    /** 工作线程执行任务前的现场句柄。 */
    static final class RestoreToken {
        private final TraceContext previousTrace;
        private final ObservationContext previousObs;
        private final Map<String, String> previousMdc;

        RestoreToken(TraceContext previousTrace, ObservationContext previousObs,
                     Map<String, String> previousMdc) {
            this.previousTrace = previousTrace;
            this.previousObs = previousObs;
            this.previousMdc = previousMdc == null ? null : new HashMap<>(previousMdc);
        }

        void restorePrevious() {
            // 必须先清空，防止本任务写入的值泄漏给线程上的下一个任务
            TraceContextHolder.clear();
            ObservationHolder.clear();
            MDC.clear();
            if (previousTrace != null) {
                TraceContextHolder.set(previousTrace);
            }
            if (previousObs != null) {
                ObservationHolder.set(previousObs);
            }
            if (previousMdc != null) {
                MDC.setContextMap(previousMdc);
            }
        }
    }
}
