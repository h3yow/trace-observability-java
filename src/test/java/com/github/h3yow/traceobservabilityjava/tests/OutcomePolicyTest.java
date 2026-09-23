package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import com.github.h3yow.traceobservabilityjava.observation.OutcomePolicy;
import com.github.h3yow.traceobservabilityjava.service.BusinessRejectedException;
import com.github.h3yow.traceobservabilityjava.service.DependencyTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 归类口径单测：四类结果必须可区分，且沿异常包装链也能正确识别超时。
 */
class OutcomePolicyTest {

    private static final Logger log = LoggerFactory.getLogger(OutcomePolicyTest.class);
    private final OutcomePolicy policy = new OutcomePolicy();

    @Test
    @DisplayName("无异常归类 SUCCESS")
    void successOnNull() {
        log.info("[输入] error=null | [依据] 无异常 => SUCCESS");
        assertEquals(Outcome.SUCCESS, policy.classify(null));
    }

    @Test
    @DisplayName("业务拒绝异常归类 BUSINESS_REJECTED")
    void rejected() {
        BusinessRejectedException e = new BusinessRejectedException("not eligible");
        log.info("[输入] {} | [依据] instanceof BusinessRejectedException => BUSINESS_REJECTED",
                e.getClass().getSimpleName());
        assertEquals(Outcome.BUSINESS_REJECTED, policy.classify(e));
    }

    @Test
    @DisplayName("依赖超时异常归类 DEPENDENCY_TIMEOUT")
    void timeout() {
        DependencyTimeoutException e = new DependencyTimeoutException("budget exceeded");
        log.info("[输入] {} | [依据] instanceof DependencyTimeoutException => DEPENDENCY_TIMEOUT",
                e.getClass().getSimpleName());
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, policy.classify(e));
    }

    @Test
    @DisplayName("JDK TimeoutException 同样归类 DEPENDENCY_TIMEOUT")
    void jdkTimeout() {
        TimeoutException e = new TimeoutException("read timed out");
        log.info("[输入] java.util.concurrent.TimeoutException | [依据] instanceof TimeoutException");
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, policy.classify(e));
    }

    @Test
    @DisplayName("深层包装的超时异常（CompletionException 嵌套）仍须识别为超时")
    void wrappedTimeout() {
        Exception e = new CompletionException(new IOException(
                new DependencyTimeoutException("nested timeout")));
        log.info("[输入] CompletionException->IOException->DependencyTimeoutException | "
                + "[依据] 沿 cause 链查找超时类型 => DEPENDENCY_TIMEOUT");
        assertEquals(Outcome.DEPENDENCY_TIMEOUT, policy.classify(e));
    }

    @Test
    @DisplayName("其他异常归类 UNEXPECTED_ERROR")
    void unexpected() {
        IllegalStateException e = new IllegalStateException("boom");
        log.info("[输入] IllegalStateException | [依据] 非超时非业务拒绝 => UNEXPECTED_ERROR");
        assertEquals(Outcome.UNEXPECTED_ERROR, policy.classify(e));
    }

    @Test
    @DisplayName("HTTP 状态码归类：2xx SUCCESS / 4xx BUSINESS_REJECTED / 5xx UNEXPECTED")
    void byStatus() {
        for (int status : new int[]{200, 201, 204}) {
            log.info("[输入] status={} | [依据] 2xx => SUCCESS", status);
            assertEquals(Outcome.SUCCESS, policy.classifyByStatus(status));
        }
        for (int status : new int[]{400, 404, 422}) {
            log.info("[输入] status={} | [依据] 4xx => BUSINESS_REJECTED", status);
            assertEquals(Outcome.BUSINESS_REJECTED, policy.classifyByStatus(status));
        }
        for (int status : new int[]{500, 502, 503}) {
            log.info("[输入] status={} | [依据] 5xx => UNEXPECTED_ERROR", status);
            assertEquals(Outcome.UNEXPECTED_ERROR, policy.classifyByStatus(status));
        }
    }
}
