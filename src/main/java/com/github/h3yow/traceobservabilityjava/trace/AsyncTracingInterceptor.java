package com.github.h3yow.traceobservabilityjava.trace;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

/**
 * 精确识别"控制器返回了异步返回值"的拦截器。
 *
 * 不用 {@code request.isAsyncStarted()} 判定：在同时注册 ASYNC 分发后，
 * 该标志在普通（同步）请求结束时语义并不稳定。Spring 仅在控制器真正返回
 * {@code CompletableFuture}/{@code Callable}/{@code WebAsyncTask} 等异步结果时
 * 才回调 {@link #afterConcurrentHandlingStarted}，因此以此作为唯一的
 * "结案推迟到 ASYNC 重分发"信号。
 */
public class AsyncTracingInterceptor implements AsyncHandlerInterceptor {

    @Override
    public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                               HttpServletResponse response,
                                               Object handler) {
        request.setAttribute(TraceFilter.ASYNC_PENDING_ATTRIBUTE, Boolean.TRUE);
    }

    // 以下两个回调在异步场景下不会被调用，保持默认空实现即可。
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        // no-op
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // no-op：结案统一由 TraceFilter 负责
    }
}
