package com.github.h3yow.traceobservabilityjava.observability;

/**
 * 请求最终归类，互斥且每条请求唯一。
 */
public enum OutcomeCategory {

    /** 正常完成：2xx 响应且无异常 */
    SUCCESS,
    /** 业务拒绝：业务规则不接受（如参数非法、余额不足等 4xx） */
    BUSINESS_REJECTED,
    /** 依赖超时：调用下游依赖时超时 */
    DEPENDENCY_TIMEOUT,
    /** 未预期异常：其余服务端故障（5xx、未知错误） */
    UNEXPECTED_ERROR
}
