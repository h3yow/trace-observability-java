package com.github.h3yow.traceobservabilityjava.observation;

import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求级观测句柄，随 TraceContext 快照一起进入线程池，
 * 因此同步阶段与异步/后台阶段共享同一个 {@link Observation}。
 *
 * <p>业务代码在重试、嵌套调用、分支中用它上报"尝试结果"，
 * 但不决定请求最终归类（最终归类由入口过滤器一次性落定）。
 */
public final class ObservationContext {

    private static final Logger log = LoggerFactory.getLogger(ObservationContext.class);

    private final Observation observation;
    private final AtomicLong attemptSequence = new AtomicLong();

    public ObservationContext(Observation observation) {
        this.observation = observation;
    }

    public Observation observation() {
        return observation;
    }

    /**
     * 上报一次依赖尝试，自动生成 attemptId。
     * 适用于"每次调用都是独立尝试"的场景。
     */
    public boolean recordAttempt(Outcome outcome, long durationNanos) {
        String attemptId = "auto-" + attemptSequence.incrementAndGet() + "-" + UUID.randomUUID();
        return doRecord(attemptId, outcome, durationNanos);
    }

    /**
     * 以显式 attemptId 上报：重试同一个 attemptId 不会重复计数。
     * 典型用法：把"逻辑调用编号"作为 attemptId，重试保持同一编号。
     */
    public boolean recordAttempt(String attemptId, Outcome outcome, long durationNanos) {
        return doRecord(attemptId, outcome, durationNanos);
    }

    private boolean doRecord(String attemptId, Outcome outcome, long durationNanos) {
        boolean accepted = observation.recordAttempt(attemptId, outcome, durationNanos);
        String traceId = observation.traceId();
        if (accepted) {
            log.info("attempt_recorded traceId={} attemptId={} outcome={} durationNanos={}",
                    traceId, attemptId, outcome, durationNanos);
        } else {
            log.warn("attempt_duplicate_ignored traceId={} attemptId={} outcome={} "
                    + "reason='same attemptId already recorded'", traceId, attemptId, outcome);
        }
        return accepted;
    }

    /** 从当前线程取句柄（取不到时返回 null，由调用方决定是否报错）。 */
    public static ObservationContext current() {
        if (TraceContextHolder.get() == null) {
            return null;
        }
        return ObservationHolder.get();
    }
}
