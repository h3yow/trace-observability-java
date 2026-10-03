package com.github.h3yow.traceobservabilityjava.trace;

import com.github.h3yow.traceobservabilityjava.observation.Observation;
import com.github.h3yow.traceobservabilityjava.observation.ObservationContext;
import com.github.h3yow.traceobservabilityjava.observation.ObservationHolder;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import com.github.h3yow.traceobservabilityjava.observation.OutcomePolicy;
import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
import jakarta.servlet.AsyncContext;
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

        String inboundCustom = httpRequest.getHeader(propagator.headerName());
        String inboundTraceparent = httpRequest.getHeader(Traceparent.HEADER);
        // 唯一取舍：W3C 合法 > 自定义头合法 > 重新生成；一次请求只有一套标识
        TracePropagator.InboundDecision decision = propagator.decide(inboundCustom, inboundTraceparent);
        TraceContext root = propagator.createRootContextFor(decision);
        String traceSource = decision.source().name().toLowerCase(java.util.Locale.ROOT);
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
            // 回写最终实际采用的标识：自定义头 + W3C 标准头，方便任一侧上游关联
            httpResponse.setHeader(propagator.headerName(), root.traceId());
            httpResponse.setHeader(Traceparent.HEADER,
                    Traceparent.format(root.traceId(), root.spanId()));
        }

        long startNanos = System.nanoTime();
        Throwable failure = null;
        log.info("request_start method={} path={} traceSource={} inboundTraceId={} inboundTraceparent={}",
                httpRequest.getMethod(), pathOf(httpRequest), traceSource,
                decision.source() == TracePropagator.Source.UPSTREAM ? inboundCustom.trim() : "-",
                decision.source() == TracePropagator.Source.W3C ? inboundTraceparent.trim() : "-");

        try {
            chain.doFilter(httpRequest, httpResponse);
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            if (failure == null && httpRequest.isAsyncStarted()) {
                // 真异步：控制器已把结果交给后台线程（如返回 CompletableFuture），
                // 入口线程到此即可释放，归类推迟到异步真正完成时落定，
                // 保证"执行中/完成回调/出错"全程仍挂在同一条链路上且只落定一次。
                registerAsyncSettlement(httpRequest, observation, root, startNanos);
                log.info("request_async_deferred traceId={} path={} note='入口线程释放，归类随异步完成落定'",
                        root.traceId(), pathOf(httpRequest));
            } else {
                settleAndLog(httpRequest, httpResponse, observation, root, startNanos, failure);
            }

            // 关键：无论成功失败、是否异步，入口线程必须清理干净
            TraceContextHolder.clear();
            ObservationHolder.clear();
            MDC.clear();
        }
    }

    /**
     * 给异步请求注册完成监听：异步结果（成功或异常）写完后由容器回调 onComplete，
     * 在那里做唯一一次归类落定。onError/onTimeout 不直接落定——容器随后仍会
     * 触发 onComplete，统一在一个地方 settle，避免迟到结论覆盖先到结论。
     */
    private void registerAsyncSettlement(HttpServletRequest request, Observation observation,
                                         TraceContext root, long startNanos) {
        AsyncContext asyncContext = request.getAsyncContext();
        asyncContext.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                HttpServletRequest req = (HttpServletRequest) event.getSuppliedRequest();
                HttpServletResponse res = (HttpServletResponse) event.getSuppliedResponse();
                settleAndLog(req, res, observation, root, startNanos, null);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                log.warn("request_async_timeout traceId={} note='等待 onComplete 统一落定'",
                        root.traceId());
            }

            @Override
            public void onError(AsyncEvent event) {
                log.warn("request_async_error traceId={} error={} note='等待 onComplete 统一落定'",
                        root.traceId(),
                        event.getThrowable() == null ? "-" : event.getThrowable().getClass().getSimpleName());
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // 异步链上再次 startAsync（如异步中再派生）时无需处理，监听随上下文保留
            }
        });
    }

    /**
     * 唯一归类落定点（同步路径在入口线程 finally 调用；异步路径在 onComplete 回调调用）。
     * 归类口径：有效异常优先（能区分超时/业务拒绝/未预期），否则看 HTTP 状态码。
     * CAS 保证先到结论不被后到覆盖、指标恰好计数一次。
     */
    private void settleAndLog(HttpServletRequest httpRequest, HttpServletResponse httpResponse,
                              Observation observation, TraceContext root, long startNanos,
                              Throwable failure) {
        // 异步回调运行在容器线程上，ThreadLocal 没有上下文：先补 MDC 让日志可关联，结束再清
        boolean mdcOwnedByUs = MDC.get("traceId") == null;
        if (mdcOwnedByUs) {
            MDC.put("traceId", root.traceId());
            MDC.put("spanId", root.spanId());
            MDC.put("requestId", root.requestId());
        }
        try {
            long durationNanos = System.nanoTime() - startNanos;
            int httpStatus = httpResponse.getStatus();
            Throwable handlerError = RequestObservationAccess.getError(httpRequest);
            Throwable effectiveError = failure != null ? failure : handlerError;

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
        } finally {
            if (mdcOwnedByUs) {
                MDC.clear();
            }
        }
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String qs = request.getQueryString();
        return qs == null ? uri : uri + "?" + qs;
    }
}
