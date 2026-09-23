package com.github.h3yow.traceobservabilityjava.observability;

/**
 * 请求处理过程中的一次尝试/分支事件（依赖调用、重试、异步子任务等），
 * 仅用于解释链路过程，不参与最终归类与计数。
 *
 * @param traceId         所属链路标识
 * @param spanId          本次尝试所在 span 的标识
 * @param parentSpanId    父 span 标识
 * @param operation       操作名（如 dependency:pay）
 * @param attempt         第几次尝试（从 1 开始）
 * @param category        本次尝试自身的归类（可能与请求最终归类不同）
 * @param elapsedNanos    本次尝试耗时
 * @param errorType       失败时的异常类型名
 * @param message         描述信息
 * @param timestampMillis 事件时间戳
 */
public record SpanEvent(String traceId,
                        String spanId,
                        String parentSpanId,
                        String operation,
                        int attempt,
                        OutcomeCategory category,
                        long elapsedNanos,
                        String errorType,
                        String message,
                        long timestampMillis) {
}
