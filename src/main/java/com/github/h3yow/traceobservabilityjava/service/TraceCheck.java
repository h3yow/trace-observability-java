package com.github.h3yow.traceobservabilityjava.service;

import com.github.h3yow.traceobservabilityjava.observation.ObservationContext;
import com.github.h3yow.traceobservabilityjava.observation.ObservationHolder;
import com.github.h3yow.traceobservabilityjava.trace.TraceContext;
import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在工作线程上校验追踪上下文确实随任务传播过来，并把证据写入结构化日志。
 */
final class TraceCheck {

    private static final Logger log = LoggerFactory.getLogger(TraceCheck.class);

    private TraceCheck() {}

    static void onWorkerThread(String stage) {
        TraceContext ctx = TraceContextHolder.get();
        ObservationContext obs = ObservationHolder.get();
        String worker = Thread.currentThread().getName();
        if (ctx == null) {
            // 出现这条日志即说明传播被破坏，测试会通过指标/断言拦截
            log.error("trace_leak_detected stage={} worker={} traceContext=null", stage, worker);
            throw new IllegalStateException("trace context missing on worker thread at " + stage);
        }
        if (obs == null) {
            log.error("trace_leak_detected stage={} worker={} observation=null traceId={}",
                    stage, worker, ctx.traceId());
            throw new IllegalStateException("observation context missing on worker thread");
        }
        log.info("worker_context_ok stage={} worker={} traceId={} spanId={} requestId={}",
                stage, worker, ctx.traceId(), ctx.spanId(), ctx.requestId());
    }
}
