package com.github.h3yow.traceobservabilityjava.trace;

import com.github.h3yow.traceobservabilityjava.observation.Observation;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 在过滤器与异常处理器之间传递本次请求的观测对象/最终异常（基于 request 属性，请求结束即回收）。
 */
public final class RequestObservationAccess {

    public static final String ATTR_OBSERVATION = RequestObservationAccess.class.getName() + ".observation";
    public static final String ATTR_ERROR = RequestObservationAccess.class.getName() + ".error";

    private RequestObservationAccess() {}

    public static void setObservation(HttpServletRequest request, Observation observation) {
        request.setAttribute(ATTR_OBSERVATION, observation);
    }

    public static Observation getObservation(HttpServletRequest request) {
        return (Observation) request.getAttribute(ATTR_OBSERVATION);
    }

    public static void setError(HttpServletRequest request, Throwable error) {
        request.setAttribute(ATTR_ERROR, error);
    }

    public static Throwable getError(HttpServletRequest request) {
        return (Throwable) request.getAttribute(ATTR_ERROR);
    }
}
