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
        log.warn("business_rejected traceId={} message={}", currentTraceId(), e.getMessage());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_REJECTED", e.getMessage());
    }

    @ExceptionHandler(DependencyTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(DependencyTimeoutException e,
                                                             HttpServletRequest request) {
        RequestObservationAccess.setError(request, e);
        log.error("dependency_timeout traceId={} message={}", currentTraceId(), e.getMessage());
        return build(HttpStatus.GATEWAY_TIMEOUT, "DEPENDENCY_TIMEOUT", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e,
                                                                HttpServletRequest request) {
        RequestObservationAccess.setError(request, e);
        log.error("unexpected_error traceId={}", currentTraceId(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "UNEXPECTED_ERROR", e.getClass().getSimpleName());
    }

    private static String currentTraceId() {
        return TraceContextHolder.isPresent() ? TraceContextHolder.get().traceId() : "-";
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("status", status.value());
        body.put("message", message);
        body.put("traceId", currentTraceId());
        return ResponseEntity.status(status).body(body);
    }
}
