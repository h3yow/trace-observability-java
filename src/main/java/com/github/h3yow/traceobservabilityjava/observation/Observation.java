package com.github.h3yow.traceobservabilityjava.observation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单个请求的观测聚合。
 *
 * <p>两层防重复：
 * <ol>
 *   <li><b>尝试层去重</b>：重试/嵌套调用用同一 attemptId 重复上报时，仅第一次生效，
 *       返回 {@code false} 告知"已忽略"；不同 attemptId（真正的多次尝试）各自记录。</li>
 *   <li><b>结论层 CAS</b>：请求最终归类通过 {@link AtomicReference#compareAndSet}
 *       一次性落定。无论分支以何种顺序结束、是否有迟到的二次 settle，
 *       先落定的结论都不会被后到的覆盖，且指标只在首次落定时计数一次。</li>
 * </ol>
 */
public final class Observation {

    /** 一次依赖尝试的记录（不可变值对象，供 /internal 端点解释链路）。 */
    public record Attempt(String attemptId, Outcome outcome, long durationNanos) {}

    private final String traceId;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final AtomicReference<Outcome> finalOutcome = new AtomicReference<>();
    private final AtomicBoolean counted = new AtomicBoolean(false);
    private volatile long finalDurationNanos = -1L;
    private volatile int finalHttpStatus = -1;
    private volatile long settledAtMillis = -1L;

    public Observation(String traceId) {
        this.traceId = traceId;
    }

    public String traceId() {
        return traceId;
    }

    /**
     * 记录一次尝试结果。按 attemptId 幂等去重。
     * @return true 首次记录；false 该 attemptId 已存在，本次被忽略（不重复计数）
     */
    public boolean recordAttempt(String attemptId, Outcome outcome, long durationNanos) {
        Attempt attempt = new Attempt(attemptId, outcome, Math.max(0L, durationNanos));
        return attempts.putIfAbsent(attemptId, attempt) == null;
    }

    public List<Attempt> attempts() {
        return List.copyOf(attempts.values());
    }

    public int attemptCount() {
        return attempts.size();
    }

    /**
     * 落定请求最终归类。CAS 语义：仅第一次调用返回 true 并触发一次指标计数；
     * 后续任何调用（迟到分支、重复回调）一律返回 false，原结论不变。
     */
    public boolean settleFinal(Outcome outcome, long durationNanos, int httpStatus) {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (finalOutcome.compareAndSet(null, outcome)) {
            this.finalDurationNanos = Math.max(0L, durationNanos);
            this.finalHttpStatus = httpStatus;
            this.settledAtMillis = System.currentTimeMillis();
            counted.set(true);
            return true;
        }
        return false;
    }

    public boolean isSettled() {
        return finalOutcome.get() != null;
    }

    public Outcome finalOutcome() {
        return finalOutcome.get();
    }

    public long finalDurationNanos() {
        return finalDurationNanos;
    }

    public int finalHttpStatus() {
        return finalHttpStatus;
    }

    public long settledAtMillis() {
        return settledAtMillis;
    }
}
