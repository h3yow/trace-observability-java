package com.github.h3yow.traceobservabilityjava.service;

/** 依赖超时异常，归类为 DEPENDENCY_TIMEOUT。 */
public class DependencyTimeoutException extends RuntimeException {
    public DependencyTimeoutException(String message) {
        super(message);
    }
}
