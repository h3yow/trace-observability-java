package com.github.h3yow.traceobservabilityjava.domain;

/**
 * 依赖超时：下游依赖在限定时间内未返回。
 */
public class DependencyTimeoutException extends RuntimeException {

    public DependencyTimeoutException(String message) {
        super(message);
    }

    public DependencyTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
