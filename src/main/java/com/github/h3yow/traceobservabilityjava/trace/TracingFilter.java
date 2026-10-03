package com.github.h3yow.traceobservabilityjava.trace;

import com.github.h3yow.traceobservabilityjava.observation.Observation;
import com.github.h3yow.traceobservabilityjava.observation.ObservationContext;
import com.github.h3yow.traceobservabilityjava.observation.ObservationHolder;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import com.github.h3yow.traceobservabilityjava.observation.OutcomePolicy;
import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
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

        // ===== 追踪标识取舍规则（稳定、唯一）=====
        // 1) traceparent 合法（W3C 严格校验，含全零拒绝）→ 采用其 trace-id，来源 w3c；
        // 2) 否则自定义头合法 → 沿用，来源 upstream（原有行为不变）；
        // 3) 都缺失/非法 → 重新生成，来源 generated。
        // 一条请求只可能落进其中一个分支，日志/指标/响应头共用同一个最终标识。
        String inbound = httpRequest.getHeader(propagator.headerName());
        String traceparent = httpRequest.getHeader(W3cTraceparent.HEADER);
        W3cTraceparent.Parsed w3c = W3cTraceparent.parse(traceparent);
        boolean customValid = propagator.isValid(inbound);

        TraceContext root;
        String traceSource;
        if (w3c != null) {
            root = propagator.createRootContext(w3c.traceId(), w3c.parentSpanId());
            traceSource = "w3c";
        } else if (customValid) {
            root = propagator.createRootContext(inbound);
            traceSource = "upstream";
        } else {
            root = propagator.createRootContext(null);
            traceSource = "generated";
        }
        if (traceparent != null && w3c == null) {
            log.warn("traceparent_invalid traceId={} value='{}' fallback={}",
                    root.traceId(), traceparent, traceSource);
        }

        Observation observation = registry.getOrCreate(root.traceId());
        ObservationContext observationContext = new ObservationContext(observation);

        TraceContextHolder.set(root);
        ObservationHolder.set(observationContext);
        MDC.put("traceId", root.traceId());
        MDC.put("spanId", root.spanId());
        MDC.put("requestId", root.requestId());
        MDC.put("traceSource", traceSource);

        RequestObservationAccess.setObservation(httpRequest, observation);
        if (httpResponse != null) {
            // 回写最终实际采用的标识：自定义头（原行为）+ 标准 traceparent（供上游关联）
            httpResponse.setHeader(propagator.headerName(), root.traceId());
            httpResponse.setHeader(W3cTraceparent.HEADER,
                    W3cTraceparent.format(root.traceId(), root.spanId()));
        }

        long startNanos = System.nanoTime();
        Throwable failure = null;
        log.info("request_start method={} path={} traceSource={} inboundTraceId={} traceparent={}",
                httpRequest.getMethod(), pathOf(httpRequest), traceSource,
                customValid ? inbound.trim() : "-", traceparent == null ? "-" : traceparent);

        try {
            chain.doFilter(httpRequest, httpResponse);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            if (failure == null && httpRequest.isAsyncStarted()) {
                // ===== 真异步：入口线程立即释放，结论延迟到异步周期结束时落定 =====
                // 控制器已把结果交给工作线程（CompletableFuture/DeferredResult），
                // 此刻响应尚未写出，绝不能提前下结论。注册 AsyncListener：
                // onComplete/onError/onTimeout 时用最终状态落定一次（CAS 保证唯一）。
                registerAsyncSettlement(httpRequest, httpResponse, observation, root, startNanos);
                // 兜底：若注册监听前异步周期已结束，isAsyncStarted 变 false，直接落定。
                // 与监听器并发触发也没关系——settleFinal 是 CAS，先到为准、只计数一次。
                if (!httpRequest.isAsyncStarted()) {
                    settle(httpRequest, httpResponse, observation, root, startNanos, null);
                }
            } else {
                // 同步链路（或链上异常）：立即以最终状态落定
                settle(httpRequest, httpResponse, observation, root, startNanos, failure);
            }

            // 关键：无论成功失败、是否异步，入口线程必须清理干净
            TraceContextHolder.clear();
            ObservationHolder.clear();
            MDC.clear();
        }
    }

    /** 为异步请求注册周期监听器：完成/出错/超时三种结局都汇到同一个 settle。 */
    private void registerAsyncSettlement(HttpServletRequest request, HttpServletResponse response,
                                         Observation observation, TraceContext root, long startNanos) {
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                // 异步派发已结束：异常处理器（若有）已把最终异常挂到 request 属性
                settle(request, response, observation, root, startNanos, null);
            }

            @Override
            public void onError(AsyncEvent event) {
                settle(request, response, observation, root, startNanos, event.getThrowable());
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                settle(request, response, observation, root, startNanos,
                        new java.util.concurrent.TimeoutException("async request timed out"));
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 新一轮异步周期（如超时后重试派发）：无需动作，监听随周期保留
            }
        });
        log.debug("async_settlement_registered traceId={} path={}", root.traceId(), pathOf(request));
    }

    /**
     * 唯一落定入口（同步 finally 与异步监听器共用）：
     * 有效异常优先，否则按 HTTP 状态码归类；CAS 保证全局只落定/计数一次，
     * 迟到的重复触发（监听器与兜底、容器重复回调）一律被忽略且不覆盖先到结论。
     */
    private void settle(HttpServletRequest request, HttpServletResponse response,
                        Observation observation, TraceContext root, long startNanos,
                        Throwable failure) {
        long durationNanos = System.nanoTime() - startNanos;
        int httpStatus = response.getStatus();
        Throwable handlerError = RequestObservationAccess.getError(request);
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
                root.traceId(), pathOf(request), httpStatus, outcome, settled,
                durationNanos, observation.attemptCount(), basis, !settled);
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String qs = request.getQueryString();
        return qs == null ? uri : uri + "?" + qs;
    }
}
