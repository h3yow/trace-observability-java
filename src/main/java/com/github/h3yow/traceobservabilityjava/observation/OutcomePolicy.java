package com.github.h3yow.traceobservabilityjava.observation;

import com.github.h3yow.traceobservabilityjava.service.BusinessRejectedException;
import com.github.h3yow.traceobservabilityjava.service.DependencyTimeoutException;

import java.util.concurrent.TimeoutException;

/**
 * 归类口径（唯一事实来源），四个类别互斥：
 * <ul>
 *   <li>{@link Outcome#DEPENDENCY_TIMEOUT}：下游依赖超时
 *       （{@link DependencyTimeoutException} / {@link TimeoutException}，或包装异常链中含它们）</li>
 *   <li>{@link Outcome#BUSINESS_REJECTED}：业务拒绝（{@link BusinessRejectedException}
 *       或 HTTP 4xx）</li>
 *   <li>{@link Outcome#UNEXPECTED_ERROR}：其余未预期异常 / HTTP 5xx</li>
 *   <li>{@link Outcome#SUCCESS}：正常完成（HTTP 2xx）</li>
 * </ul>
 *
 * <p>判定沿异常因果链（cause chain）查找，超时优先级高于业务拒绝，
 * 因为"业务先拒绝、随后又超时"这种并存情况下超时通常是更需要关注的依赖问题。
 */
public final class OutcomePolicy {

    /** 依据异常类型判定归类（沿 cause 链查找）。 */
    public Outcome classify(Throwable error) {
        if (error == null) {
            return Outcome.SUCCESS;
        }
        Throwable cursor = error;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            if (cursor instanceof DependencyTimeoutException || cursor instanceof TimeoutException) {
                return Outcome.DEPENDENCY_TIMEOUT;
            }
            cursor = cursor.getCause();
        }
        if (hasCause(error, BusinessRejectedException.class)) {
            return Outcome.BUSINESS_REJECTED;
        }
        return Outcome.UNEXPECTED_ERROR;
    }

    /** 依据 HTTP 状态码判定归类。 */
    public Outcome classifyByStatus(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return Outcome.SUCCESS;
        }
        if (httpStatus >= 400 && httpStatus < 500) {
            return Outcome.BUSINESS_REJECTED;
        }
        return Outcome.UNEXPECTED_ERROR;
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable cursor = error;
        int guard = 0;
        while (cursor != null && guard++ < 32) {
            if (type.isInstance(cursor)) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
