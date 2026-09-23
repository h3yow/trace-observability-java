package com.github.h3yow.traceobservabilityjava.trace;

import com.github.h3yow.traceobservabilityjava.observability.ObservationStore;
import com.github.h3yow.traceobservabilityjava.observability.OutcomeCategory;
import com.github.h3yow.traceobservabilityjava.observability.OutcomeResolver;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.EnumSet;

/**
 * 请求可观测链路的入口与唯一结案点。
 *
 * 处理流程：
 * <ol>
 *   <li>从 X-Trace-Id / traceparent 解析上游标识，非法或缺失则生成；</li>
 *   <li>建立根 span、ThreadLocal 与 MDC，observation 以 traceId 登记；</li>
 *   <li>执行业务；异常由 {@code GlobalExceptionHandler} 写入 ERROR_ATTRIBUTE
 *       （也兼容未被 Advice 捕获、由容器错误转发再次进入过滤器的情况）；</li>
 *   <li>finally 中裁决归类并 CAS 结案（恰好一次），写响应头与结构化日志；</li>
 *   <li>无条件 clear，防止 Tomcat 工作线程复用泄漏。</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_ATTRIBUTE = TraceFilter.class.getName() + ".TRACE_ID";
    public static final String START_NANOS_ATTRIBUTE = TraceFilter.class.getName() + ".START_NANOS";
    public static final String UPSTREAM_PROVIDED_ATTRIBUTE =
            TraceFilter.class.getName() + ".UPSTREAM_PROVIDED";
    public static final String ERROR_ATTRIBUTE = TraceFilter.class.getName() + ".ERROR";
    /**
     * 控制器返回异步类型时由 {@code AsyncTracingInterceptor} 设置；
     * 过滤器只在该标记存在时把结案推迟到 ASYNC 重分发，
     * 不依赖在容器里语义不稳定的 {@code request.isAsyncStarted()}。
     */
    public static final String ASYNC_PENDING_ATTRIBUTE =
            TraceFilter.class.getName() + ".ASYNC_PENDING";
    /** ASYNC 重分发时为 true，表示本次必须完成结案。 */
    public static final String ASYNC_DISPATCH_ATTRIBUTE =
            TraceFilter.class.getName() + ".ASYNC_DISPATCH";

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);

    private final ObservationStore observations;

    public TraceFilter(ObservationStore observations) {
        this.observations = observations;
    }

    /**
     * 必须在 ASYNC 分发上执行：控制器返回 CompletableFuture 时，
     * 真实状态码与异常只在异步完成后的重分发中可见，结案必须发生在那里。
     * （OncePerRequestFilter 默认跳过 ASYNC。）
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /** 显式把过滤器注册到 REQUEST + ASYNC + ERROR 三种分发类型。 */
    @Bean
    public FilterRegistrationBean<TraceFilter> traceFilterRegistration(TraceFilter traceFilter) {
        FilterRegistrationBean<TraceFilter> registration = new FilterRegistrationBean<>(traceFilter);
        registration.setDispatcherTypes(EnumSet.of(
                DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean asyncDispatch = request.getDispatcherType() == DispatcherType.ASYNC;

        // ASYNC 重分发：复用首次 REQUEST 分发保存在 request 上的链路信息。
        String existingTraceId = (String) request.getAttribute(TRACE_ID_ATTRIBUTE);
        Long existingStart = (Long) request.getAttribute(START_NANOS_ATTRIBUTE);

        final String traceId;
        final long startNanos;
        final boolean upstreamProvided;

        if (asyncDispatch && existingTraceId != null && existingStart != null) {
            traceId = existingTraceId;
            startNanos = existingStart;
            Boolean upstream = (Boolean) request.getAttribute(UPSTREAM_PROVIDED_ATTRIBUTE);
            upstreamProvided = upstream != null && upstream;
            request.setAttribute(ASYNC_DISPATCH_ATTRIBUTE, Boolean.TRUE);
            // ASYNC 分发线程也需要 MDC，直到结案后清理
            TraceContextHolder.push(TraceContext.root(traceId, TraceIds.newSpanId(), upstreamProvided));
        } else {
            String resolvedUpstream = TraceIds.extractUpstreamTraceId(
                    request.getHeader(TraceIds.TRACE_ID_HEADER),
                    request.getHeader(TraceIds.TRACE_PARENT_HEADER));
            traceId = TraceIds.resolveOrGenerate(resolvedUpstream);
            upstreamProvided = resolvedUpstream != null;
            startNanos = System.nanoTime();

            TraceContext context = TraceContext.root(traceId, TraceIds.newSpanId(), upstreamProvided);
            TraceContextHolder.push(context);
            request.setAttribute(TRACE_ID_ATTRIBUTE, traceId);
            request.setAttribute(START_NANOS_ATTRIBUTE, startNanos);
            request.setAttribute(UPSTREAM_PROVIDED_ATTRIBUTE, upstreamProvided);
            observations.start(traceId, request.getMethod(), request.getRequestURI(), upstreamProvided);

            if (!upstreamProvided) {
                log.info("入站请求缺少合法追踪标识，已生成新 traceId={} path={}",
                        traceId, request.getRequestURI());
            } else {
                log.info("入站请求沿用上游 traceId={} path={}", traceId, request.getRequestURI());
            }
        }

        response.setHeader(TraceIds.TRACE_ID_HEADER, traceId);

        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
            failure = (Throwable) request.getAttribute(ERROR_ATTRIBUTE);
        } catch (Throwable ex) {
            // 未被 @RestControllerAdvice 捕获的异常（如过滤器链内异常）
            failure = ex;
            if (ex instanceof ServletException servletEx && servletEx.getCause() != null) {
                failure = servletEx.getCause();
            }
            throw ex;
        } finally {
            // 控制器真正返回异步结果时（由拦截器显式标记），首次分发不结案，
            // 等 ASYNC 重分发携带最终状态码/异常回来再定案。
            boolean deferToAsyncDispatch =
                    Boolean.TRUE.equals(request.getAttribute(ASYNC_PENDING_ATTRIBUTE))
                            && !asyncDispatch;
            if (deferToAsyncDispatch) {
                // 异步工作线程自带传播的上下文；这里只移除请求的附着，不做结案。
                TraceContextHolder.clear();
                return;
            }
            long elapsedNanos = System.nanoTime() - startNanos;
            int status = response.getStatus();
            OutcomeCategory category = OutcomeResolver.resolve(status, failure);
            String errorType = failure == null ? null : failure.getClass().getName();

            // ASYNC 重新分发回来时（dispatcherType=ASYNC）同样进入本过滤器，
            // CAS 保证仍然只结案一次。
            boolean finalizedNow = observations.finalize(
                    traceId, status, category, elapsedNanos, errorType);
            if (!finalizedNow) {
                log.debug("traceId={} 已存在先到结论，忽略迟到裁决 category={}",
                        traceId, category);
            } else {
                writeStructuredLog(request, traceId, status, category, elapsedNanos, failure);
            }
            // 请求边界兜底：无论上述路径如何，都清空线程状态，防止容器线程复用泄漏。
            TraceContextHolder.clear();
        }
    }

    private void writeStructuredLog(HttpServletRequest request, String traceId, int status,
                                    OutcomeCategory category, long elapsedNanos,
                                    Throwable failure) {
        String message = "request {} {} -> status={} outcome={} elapsedMicros={}";
        Object[] args = new Object[] {
                request.getMethod(), request.getRequestURI(), status, category.name(),
                elapsedNanos / 1_000
        };
        if (failure != null) {
            // 结构化字段仍由 MDC + 内存 appender 统一收集，异常堆栈带 traceId 输出。
            log.atInfo()
                    .setCause(failure)
                    .log(message + " error={}", appendArg(args, failure.getClass().getSimpleName()));
        } else if (category == OutcomeCategory.SUCCESS) {
            log.info(message, args);
        } else {
            log.warn(message, args);
        }
    }

    private static Object[] appendArg(Object[] args, Object extra) {
        Object[] grown = new Object[args.length + 1];
        System.arraycopy(args, 0, grown, 0, args.length);
        grown[args.length] = extra;
        return grown;
    }
}
