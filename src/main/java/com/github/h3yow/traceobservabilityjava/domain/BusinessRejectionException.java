package com.github.h3yow.traceobservabilityjava.domain;

/**
 * 业务拒绝：请求可被处理，但业务规则不接受。
 */
public class BusinessRejectionException extends RuntimeException {

    public BusinessRejectionException(String message) {
        super(message);
    }
}
