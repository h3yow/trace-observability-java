package com.github.h3yow.traceobservabilityjava.domain;

import com.github.h3yow.traceobservabilityjava.observability.ObservationStore;
import com.github.h3yow.traceobservabilityjava.observability.OutcomeCategory;
import com.github.h3yow.traceobservabilityjava.trace.TraceContext;
import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
import com.github.h3yow.traceobservabilityjava.trace.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 模拟下游依赖：按给定延迟阻塞，超过调用方指定的预算即判定超时。
 * 每次调用在当前请求下开一个子 span，并向 observation 追加一次尝试事件；
 * 单次尝试的成败只记录事件，调用方（业务层）据此决定是否重试。
 */
@Service
public class DependencyService {

    private static final Logger log = LoggerFactory.getLogger(DependencyService.class);

    private final ObservationStore observations;

    public DependencyService(ObservationStore observations) {
        this.observations = observations;
    }

    /**
     * 调用下游。
     *
     * @param operation       操作名（事件中展示）
     * @param delayMillis     下游本次处理耗时（模拟）
     * @param timeoutMillis   调用方超时预算
     * @param attempt         第几次尝试（从 1 开始）
     * @return 下游返回内容
     * @throws DependencyTimeoutException 超过预算
     * @throws IllegalStateException      调用线程没有追踪上下文（编程错误，快速暴露）
     */
    public String call(String operation, long delayMillis, long timeoutMillis, int attempt) {
        TraceContext parent = TraceContextHolder.current();
        if (parent == null) {
            throw new IllegalStateException("调用依赖时线程上不存在追踪上下文: " + Thread.currentThread());
        }
        TraceContext child = parent.withChild(TraceIds.newSpanId());
        long start = System.nanoTime();
        try (var ignored = TraceContextHolder.attach(child)) {
            log.debug("下游调用开始 operation={} attempt={} delayMillis={}", operation, attempt, delayMillis);
            if (delayMillis > timeoutMillis) {
                long elapsed = System.nanoTime() - start;
                DependencyTimeoutException error = new DependencyTimeoutException(
                        "调用下游 " + operation + " 超时: 预算 " + timeoutMillis
                                + "ms, 实际等待 " + delayMillis + "ms (attempt=" + attempt + ")");
                observations.event(child, "dependency:" + operation, attempt,
                        OutcomeCategory.DEPENDENCY_TIMEOUT, elapsed, error, null);
                log.atWarn().setCause(error).log("下游调用超时 operation={} attempt={}", operation, attempt);
                throw error;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new DependencyTimeoutException("下游调用被中断: " + operation, ex);
            }
            long elapsed = System.nanoTime() - start;
            observations.event(child, "dependency:" + operation, attempt,
                    OutcomeCategory.SUCCESS, elapsed, null, "ok");
            log.debug("下游调用成功 operation={} attempt={} elapsedMicros={}",
                    operation, attempt, elapsed / 1_000);
            return "result[" + operation + "#" + attempt + "]";
        }
    }
}
