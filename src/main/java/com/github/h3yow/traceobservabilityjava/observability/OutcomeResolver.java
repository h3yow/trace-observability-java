package com.github.h3yow.traceobservabilityjava.observability;

import com.github.h3yow.traceobservabilityjava.domain.BusinessRejectionException;
import com.github.h3yow.traceobservabilityjava.domain.DependencyTimeoutException;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * 请求结果归类口径的唯一裁决点。规则互斥、全序如下：
 * <ol>
 *   <li>异常链中存在 {@link DependencyTimeoutException}、{@link TimeoutException}
 *       或 {@link SocketTimeoutException}（典型的下游读/连接超时）
 *       → {@link OutcomeCategory#DEPENDENCY_TIMEOUT}；</li>
 *   <li>异常链中存在 {@link BusinessRejectionException} → {@link OutcomeCategory#BUSINESS_REJECTED}；</li>
 *   <li>存在其他异常 → {@link OutcomeCategory#UNEXPECTED_ERROR}；</li>
 *   <li>无异常时看状态码：4xx → BUSINESS_REJECTED，5xx → UNEXPECTED_ERROR，其余 → SUCCESS。</li>
 * </ol>
 * 注意：单次依赖尝试的超时可能被重试"挽回"，因此本裁决只在请求边界执行一次，
 * 中途事件使用自己的归类，绝不提前定案。
 */
public final class OutcomeResolver {

    private OutcomeResolver() {
    }

    public static OutcomeCategory resolve(Integer status, Throwable error) {
        if (error != null) {
            Throwable cursor = error;
            while (cursor != null) {
                if (cursor instanceof DependencyTimeoutException
                        || cursor instanceof TimeoutException
                        || cursor instanceof SocketTimeoutException) {
                    return OutcomeCategory.DEPENDENCY_TIMEOUT;
                }
                cursor = cursor.getCause();
            }
            cursor = error;
            while (cursor != null) {
                if (cursor instanceof BusinessRejectionException) {
                    return OutcomeCategory.BUSINESS_REJECTED;
                }
                cursor = cursor.getCause();
            }
            return OutcomeCategory.UNEXPECTED_ERROR;
        }
        if (status != null) {
            if (status >= 400 && status < 500) {
                return OutcomeCategory.BUSINESS_REJECTED;
            }
            if (status >= 500) {
                return OutcomeCategory.UNEXPECTED_ERROR;
            }
        }
        return OutcomeCategory.SUCCESS;
    }
}
