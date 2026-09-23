package com.github.h3yow.traceobservabilityjava.trace;

import com.github.h3yow.traceobservabilityjava.observation.Observation;
import com.github.h3yow.traceobservabilityjava.observation.ObservationContext;
import com.github.h3yow.traceobservabilityjava.observation.ObservationHolder;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import com.github.h3yow.traceobservabilityjava.observation.OutcomePolicy;
import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 请求入口过滤器，是整条可观测链路的起点与终点：
 * <ol>
 *   进入时解析/生成 traceId，建立 {@link Observation} 并写入 ThreadLocal 与 MDC；
 *   结束时依据"最终异常优先，其次 HTTP 状态码"得到唯一 Outcome，
 *       通过 CAS 一次性落定并计入指标，先到结论不被后到分支覆盖；
 *   finally 中无条件移除 ThreadLocal 与 MDC，保证 Tomcat 线程复用不泄漏。
 * </ol>
 *
 * <p>另外通过响应头回写 traceId，客户端可直接关联本次链路。
 */
public final class TracingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(TracingFilter.class);

    private final TracePropagator propagator;
    private final ObservationRegistry registry;
    private final OutcomePolicy outcomePolicy;

    public TracingFilter(TracePropagator propagator, ObservationRegistry registry,
                         OutcomePolicy outcomePolicy) {
        this.propagator = propagator;
        this.registry = registry;
        this.outcomePolicy = outcomePolicy;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String inbound = httpRequest.getHeader(propagator.headerName());
        boolean inboundValid = propagator.isValid(inbound);
        TraceContext root = propagator.createRootContext(inbound);
        Observation observation = registry.getOrCreate(root.traceId());
        ObservationContext observationContext = new ObservationContext(observation);

        TraceContextHolder.set(root);
        ObservationHolder.set(observationContext);
        MDC.put("traceId", root.traceId());
        MDC.put("spanId", root.spanId());
        MDC.put("requestId", root.requestId());
        MDC.put("traceSource", inboundValid ? "upstream" : "generated");

        RequestObservationAccess.setObservation(httpRequest, observation);
        if (httpResponse != null) {
            httpResponse.setHeader(propagator.headerName(), root.traceId());
        }

        long startNanos = System.nanoTime();
        Throwable failure = null;
        log.info("request_start method={} path={} traceSource={} inboundTraceId={}",
                httpRequest.getMethod(), pathOf(httpRequest),
                inboundValid ? "upstream" : "generated", inboundValid ? inbound.trim() : "-");

        try {
            chain.doFilter(httpRequest, httpResponse);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            int httpStatus = httpResponse.getStatus();
            Throwable handlerError = RequestObservationAccess.getError(httpRequest);
            Throwable effectiveError = failure != null ? failure : handlerError;

            // 归类口径：有效异常优先（能区分超时/业务拒绝/未预期），否则看状态码
            Outcome outcome;
            String basis;
            if (effectiveError != null) {
                outcome = outcomePolicy.classify(effectiveError);
                basis = "exception:" + effectiveError.getClass().getSimpleName();
            } else {
                outcome = outcomePolicy.classifyByStatus(httpStatus);
                basis = "status:" + httpStatus;
            }

            boolean settled = observation.settleFinal(outcome, durationNanos, httpStatus);
            if (settled) {
                registry.onSettled(observation, durationNanos);
            }

            log.info("request_end traceId={} path={} status={} outcome={} settled={} "
                            + "durationNanos={} attempts={} basis='{}' lateSettleIgnored={}",
                    root.traceId(), pathOf(httpRequest), httpStatus, outcome, settled,
                    durationNanos, observation.attemptCount(), basis, !settled);

            // 关键：无论成功失败、是否异步泄漏，入口线程必须清理干净
            TraceContextHolder.clear();
            ObservationHolder.clear();
            MDC.clear();
        }
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String qs = request.getQueryString();
        return qs == null ? uri : uri + "?" + qs;
    }
}
