package com.github.h3yow.traceobservabilityjava.observability;

import com.github.h3yow.traceobservabilityjava.domain.BusinessRejectionException;
import com.github.h3yow.traceobservabilityjava.domain.DependencyTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 归类口径单元测试：异常优先、状态码兜底，四类互斥。
 */
class OutcomeResolverTest {

    private static void logCase(String title, Object status, Object error, OutcomeCategory actual) {
        System.out.printf("[OutcomeResolverTest] %s | 输入 status=%s error=%s | 判定=%s%n",
                title, status, error == null ? "null" : error.getClass().getSimpleName(), actual);
    }

    @Test
    @DisplayName("无异常：2xx 成功，4xx 业务拒绝，5xx 未预期异常")
    void classifyByStatus() {
        assertEquals(OutcomeCategory.SUCCESS, OutcomeResolver.resolve(200, null));
        assertEquals(OutcomeCategory.SUCCESS, OutcomeResolver.resolve(204, null));
        assertEquals(OutcomeCategory.BUSINESS_REJECTED, OutcomeResolver.resolve(400, null));
        assertEquals(OutcomeCategory.BUSINESS_REJECTED, OutcomeResolver.resolve(422, null));
        assertEquals(OutcomeCategory.UNEXPECTED_ERROR, OutcomeResolver.resolve(500, null));
        assertEquals(OutcomeCategory.SUCCESS, OutcomeResolver.resolve(null, null));
        System.out.printf("[OutcomeResolverTest] 状态码兜底 | 输入=200/422/500/null, error=null | "
                + "判定=SUCCESS/BUSINESS_REJECTED/UNEXPECTED_ERROR/SUCCESS%n");
    }

    @Test
    @DisplayName("业务异常优先于 200 状态码")
    void businessExceptionWinsOverStatus() {
        OutcomeCategory category = OutcomeResolver.resolve(200, new BusinessRejectionException("拒绝"));
        assertEquals(OutcomeCategory.BUSINESS_REJECTED, category);
        logCase("异常优先于状态码", 200, new BusinessRejectionException("拒绝"), category);
    }

    @Test
    @DisplayName("超时优先级最高：包装过的超时也必须识别")
    void timeoutDetectedThroughWrapping() {
        DependencyTimeoutException direct = new DependencyTimeoutException("直接超时");
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, OutcomeResolver.resolve(500, direct));

        SocketTimeoutException socket = new SocketTimeoutException("Read timed out");
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, OutcomeResolver.resolve(500, socket));

        TimeoutException jdk = new TimeoutException("future 超时");
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, OutcomeResolver.resolve(500, jdk));

        // 超时被两层 RuntimeException 包装
        RuntimeException wrapped = new RuntimeException("wrapper1",
                new RuntimeException("wrapper2", new TimeoutException("深层超时")));
        OutcomeCategory category = OutcomeResolver.resolve(500, wrapped);
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, category);
        logCase("遍历异常 cause 链", 500, wrapped, category);
    }

    @Test
    @DisplayName("超时优先于业务异常（同一条 cause 链）")
    void timeoutBeatsBusiness() {
        TimeoutException timeout = new TimeoutException("超时");
        timeout.initCause(new BusinessRejectionException("业务原因"));
        RuntimeException wrapped = new RuntimeException("wrapper", timeout);
        // cause 链: RuntimeException -> TimeoutException -> BusinessRejectionException
        OutcomeCategory category = OutcomeResolver.resolve(500, wrapped);
        assertEquals(OutcomeCategory.DEPENDENCY_TIMEOUT, category);
        logCase("同链优先级", 500, wrapped, category);
    }

    @Test
    @DisplayName("未知异常归类为未预期异常")
    void unknownIsUnexpected() {
        OutcomeCategory category = OutcomeResolver.resolve(200, new IllegalStateException("?"));
        assertEquals(OutcomeCategory.UNEXPECTED_ERROR, category);
        logCase("未知异常", 200, new IllegalStateException("?"), category);
    }
}
