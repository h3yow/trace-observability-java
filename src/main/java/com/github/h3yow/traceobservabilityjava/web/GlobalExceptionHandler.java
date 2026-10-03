package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.service.BusinessRejectedException;
import com.github.h3yow.traceobservabilityjava.service.DependencyTimeoutException;
import com.github.h3yow.traceobservabilityjava.trace.RequestObservationAccess;
import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一异常处理：把三类异常映射为可区分的 HTTP 状态码，并把最终异常挂到 request，
 * 供 {@code TracingFilter} 用同一口径归类。
 *
 * <p>注意：这里不做指标计数（计数只发生在过滤器的 CAS 落定处），
 * 因此异常处理与过滤器谁先"得出结论"都不会重复计数或互相覆盖。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleRejected(BusinessRejectedException e,
                                                              HttpServletRequest request) {
        RequestObservationAccess.setError(request, e);
        log.warn("business_rejected traceId={} message={}", currentTraceId(request), e.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_REJECTED", e.getMessage(),
                currentTraceId(request));
    }

    @ExceptionHandler(DependencyTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(DependencyTimeoutException e,
                                                             HttpServletRequest request) {
        RequestObservationAccess.setError(request, e);
        log.error("dependency_timeout traceId={} message={}", currentTraceId(request), e.getMessage());
        return build(HttpStatus.GATEWAY_TIMEOUT, "DEPENDENCY_TIMEOUT", e.getMessage(),
                currentTraceId(request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e,
                                                                HttpServletRequest request) {
        RequestObservationAccess.setError(request, e);
        log.error("unexpected_error traceId={}", currentTraceId(request), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "UNEXPECTED_ERROR", e.getClass().getSimpleName(),
                currentTraceId(request));
    }

    /**
     * 取当前请求 traceId：同步路径走 ThreadLocal；
     * 异步（非阻塞）路径下异常处理运行在容器的异步 dispatch 线程上，ThreadLocal 为空，
     * 此时回退到请求属性里的 Observation（同一请求对象跨 dispatch 保留），
     * 保证异步异常日志与响应体里的 traceId 仍然和链路一致。
     */
    private static String currentTraceId(HttpServletRequest request) {
        if (TraceContextHolder.isPresent()) {
            return TraceContextHolder.get().traceId();
        }
        if (request != null && RequestObservationAccess.getObservation(request) != null) {
            return RequestObservationAccess.getObservation(request).traceId();
        }
        return "-";
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message,
                                                      String traceId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("status", status.value());
        body.put("message", message);
        body.put("traceId", traceId);
        return ResponseEntity.status(status).body(body);
    }
}
