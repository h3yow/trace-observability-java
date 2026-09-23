package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.domain.BusinessRejectionException;
import com.github.h3yow.traceobservabilityjava.domain.DependencyTimeoutException;
import com.github.h3yow.traceobservabilityjava.trace.TraceFilter;
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
 * 把异常映射为 HTTP 状态码，并把异常对象挂到 request 属性，
 * 使 {@link TraceFilter} 在请求边界能基于"真实异常"完成唯一归类，
 * 而不是只看状态码。
 *
 * 映射：业务拒绝→422，依赖超时→504，其余→500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessRejectionException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessRejectionException ex,
                                                              HttpServletRequest request) {
        request.setAttribute(TraceFilter.ERROR_ATTRIBUTE, ex);
        log.warn("业务拒绝 path={} reason={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(body(request, "BUSINESS_REJECTED", ex.getMessage()));
    }

    @ExceptionHandler(DependencyTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleTimeout(DependencyTimeoutException ex,
                                                             HttpServletRequest request) {
        request.setAttribute(TraceFilter.ERROR_ATTRIBUTE, ex);
        log.atError().setCause(ex)
                .log("依赖超时 path={} reason={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(body(request, "DEPENDENCY_TIMEOUT", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex,
                                                                HttpServletRequest request) {
        request.setAttribute(TraceFilter.ERROR_ATTRIBUTE, ex);
        log.atError().setCause(ex)
                .log("未预期异常 path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(request, "UNEXPECTED_ERROR",
                        ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }

    private Map<String, Object> body(HttpServletRequest request, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return body;
    }
}
