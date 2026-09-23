package com.github.h3yow.traceobservabilityjava.observation;

/**
 * 请求最终归类，四者互斥且每个请求唯一：
 * SUCCESS           正常完成
 * BUSINESS_REJECTED 业务拒绝（如 4xx 业务规则拒绝）
 * DEPENDENCY_TIMEOUT 依赖超时
 * UNEXPECTED_ERROR  未预期异常
 */
public enum Outcome {
    SUCCESS,
    BUSINESS_REJECTED,
    DEPENDENCY_TIMEOUT,
    UNEXPECTED_ERROR
}
