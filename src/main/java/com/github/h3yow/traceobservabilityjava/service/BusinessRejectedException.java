package com.github.h3yow.traceobservabilityjava.service;

/** 业务拒绝异常，归类为 BUSINESS_REJECTED。 */
public class BusinessRejectedException extends RuntimeException {
    public BusinessRejectedException(String message) {
        super(message);
    }
}
