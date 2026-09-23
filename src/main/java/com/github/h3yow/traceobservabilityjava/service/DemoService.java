package com.github.h3yow.traceobservabilityjava.service;

import com.github.h3yow.traceobservabilityjava.observation.ObservationContext;
import com.github.h3yow.traceobservabilityjava.observation.ObservationHolder;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 演示用"下游依赖"（本地模拟，不真正访问外部）：
 * 通过 scenario 触发正常返回、业务拒绝、依赖超时、未预期异常；
 * 并提供线程池异步与带重试的调用，用于验证上下文传播、尝试去重与归类。
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    public static final String SC_SUCCESS = "success";
    public static final String SC_REJECT = "reject";
    public static final String SC_TIMEOUT = "timeout";
    public static final String SC_BUG = "bug";

    /** 模拟依赖固定耗时；超时场景会 sleep 超过调用方预算。 */
    private static final long DEPENDENCY_LATENCY_MILLIS = 120L;

    private final ExecutorService executor;

    public DemoService(@Qualifier("traceAwareExecutor") ExecutorService executor) {
        this.executor = executor;
    }

    /**
     * 同步调用一次"下游依赖"。
     *
     * @param scenario      success/reject/timeout/bug
     * @param timeoutMillis 调用方预算；模拟耗时超过预算即抛 {@link DependencyTimeoutException}
     */
    public String callDependency(String scenario, long timeoutMillis) {
        return invokeOnce(scenario, timeoutMillis);
    }

    /**
     * 在线程池里调用依赖：执行线程必须能读到同一 traceId / ObservationContext。
     */
    public CompletableFuture<String> callDependencyAsync(String scenario, long timeoutMillis) {
        return CompletableFuture.supplyAsync(() -> {
            TraceCheck.onWorkerThread("callDependencyAsync");
            return invokeOnce(scenario, timeoutMillis);
        }, executor);
    }

    /**
     * 带重试的调用：
     * <ul>
     *   <li>每次真实尝试使用稳定 attemptId（attempt-1..attempt-N），
     *       若同一 attemptId 因分支/回调被重复上报，Observation 会去重；</li>
     *   <li>"重试"本身不会产生多个请求归类，最终归类仍由入口过滤器落定一次。</li>
     * </ul>
     *
     * @param failFirstN 前 N 次以超时失败，最后一次成功（验证"失败重试后成功=SUCCESS"）
     */
    public String callDependencyWithRetry(String scenario, int maxAttempts, long timeoutMillis,
                                          int failFirstN) {
        ObservationContext obs = ObservationHolder.get();
        RuntimeException lastError = null;
        for (int i = 1; i <= maxAttempts; i++) {
            long start = System.nanoTime();
            String effectiveScenario = (i <= failFirstN) ? SC_TIMEOUT : scenario;
            // 稳定 attemptId：同一逻辑尝试编号永远只有一条记录
            String attemptId = "attempt-" + i;
            try {
                String result = invokeOnceNoRecord(effectiveScenario, timeoutMillis);
                if (obs != null) {
                    obs.recordAttempt(attemptId, Outcome.SUCCESS, System.nanoTime() - start);
                }
                log.info("retry_success attemptNo={} traceIdPresent={}", i,
                        com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder.isPresent());
                return result;
            } catch (RuntimeException e) {
                lastError = e;
                Outcome attemptOutcome = e instanceof DependencyTimeoutException
                        ? Outcome.DEPENDENCY_TIMEOUT
                        : e instanceof BusinessRejectedException
                                ? Outcome.BUSINESS_REJECTED
                                : Outcome.UNEXPECTED_ERROR;
                if (obs != null) {
                    // 同一 attemptId 即使因异常处理重入而再次上报，也不会重复计数
                    obs.recordAttempt(attemptId, attemptOutcome, System.nanoTime() - start);
                }
                log.warn("retry_failed attemptNo={}/{} error={}", i, maxAttempts,
                        e.getClass().getSimpleName());
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("retry exhausted");
    }

    /**
     * 提交一个"发射后不管"的后台任务，立即返回。
     * 用于验证：请求返回后后台线程仍关联同一 traceId，且执行完不污染池内下一个任务。
     */
    public CompletableFuture<String> submitBackground(String scenario, long timeoutMillis) {
        return CompletableFuture.supplyAsync(() -> {
            TraceCheck.onWorkerThread("background");
            return invokeOnce(scenario, timeoutMillis);
        }, executor);
    }

    private String invokeOnce(String scenario, long timeoutMillis) {
        ObservationContext obs = ObservationHolder.get();
        long start = System.nanoTime();
        String attemptId = "single-" + Long.toHexString(System.nanoTime());
        try {
            String result = invokeOnceNoRecord(scenario, timeoutMillis);
            if (obs != null) {
                obs.recordAttempt(attemptId, Outcome.SUCCESS, System.nanoTime() - start);
            }
            return result;
        } catch (RuntimeException e) {
            Outcome attemptOutcome = e instanceof DependencyTimeoutException
                    ? Outcome.DEPENDENCY_TIMEOUT
                    : e instanceof BusinessRejectedException
                            ? Outcome.BUSINESS_REJECTED
                            : Outcome.UNEXPECTED_ERROR;
            if (obs != null) {
                obs.recordAttempt(attemptId, attemptOutcome, System.nanoTime() - start);
            }
            throw e;
        }
    }

    /** 真正执行一次模拟依赖调用，不做尝试记录（由调用方决定如何记录，避免重试场景重复）。 */
    private String invokeOnceNoRecord(String scenario, long timeoutMillis) {
        switch (scenario) {
            case SC_SUCCESS -> sleepQuietly(10L);
            case SC_REJECT -> throw new BusinessRejectedException("customer not eligible");
            case SC_BUG -> throw new IllegalStateException("NPE simulated in dependency");
            case SC_TIMEOUT -> {
                sleepQuietly(DEPENDENCY_LATENCY_MILLIS);
                if (timeoutMillis < DEPENDENCY_LATENCY_MILLIS) {
                    throw new DependencyTimeoutException(
                            "dependency exceeded budget " + timeoutMillis + "ms");
                }
            }
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        return "ok:" + scenario;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DependencyTimeoutException("interrupted while waiting dependency");
        }
    }
}
