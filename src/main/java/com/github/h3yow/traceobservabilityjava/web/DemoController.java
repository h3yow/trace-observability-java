package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.service.DemoService;
import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 演示端点：通过 scenario 触发不同链路结果。
 * 所有响应都回带当前 traceId，便于和本地指标/日志对账。
 */
@RestController
@RequestMapping("/api/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    /** 纯同步链路。 */
    @GetMapping("/sync")
    public Map<String, Object> sync(@RequestParam(defaultValue = "success") String scenario,
                                    @RequestParam(defaultValue = "100") long timeoutMillis) {
        String result = demoService.callDependency(scenario, timeoutMillis);
        return body(scenario, "sync", result);
    }

    /** 在线程池中执行依赖后等待返回，验证 traceId 跨线程一致。 */
    @GetMapping("/async")
    public Map<String, Object> async(@RequestParam(defaultValue = "success") String scenario,
                                     @RequestParam(defaultValue = "100") long timeoutMillis) {
        try {
            String result = demoService.callDependencyAsync(scenario, timeoutMillis).join();
            return body(scenario, "async-pool", result);
        } catch (CompletionException e) {
            // join() 会把依赖异常包成 CompletionException；解包恢复真实类型，
            // 让异常处理器与归类口径能识别 DependencyTimeoutException
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /** 带重试：默认前 1 次超时、后续成功，最终归类必须是 SUCCESS 且只计数一次。 */
    @GetMapping("/retry")
    public Map<String, Object> retry(@RequestParam(defaultValue = "timeout-then-success") String scenario,
                                     @RequestParam(defaultValue = "3") int maxAttempts,
                                     @RequestParam(defaultValue = "1") int failFirstN,
                                     @RequestParam(defaultValue = "100") long timeoutMillis) {
        // retry 端点把 scenario 作为"最终成功时的场景"，前 failFirstN 次固定超时
        String result = demoService.callDependencyWithRetry(
                DemoService.SC_SUCCESS, maxAttempts, timeoutMillis, failFirstN);
        return body("retry:" + scenario, "retry-pool-" + maxAttempts, result);
    }

    /** 发射后台任务：验证后台执行与请求关联同一 traceId，且线程清理干净。 */
    @GetMapping("/background")
    public Map<String, Object> background(@RequestParam(defaultValue = "success") String scenario,
                                          @RequestParam(defaultValue = "100") long timeoutMillis) {
        CompletableFuture<String> future = demoService.submitBackground(scenario, timeoutMillis);
        return body(scenario, "background-submitted", "future@" + Integer.toHexString(
                System.identityHashCode(future)));
    }

    /**
     * 真异步（非阻塞）：返回 {@link CompletableFuture}，Servlet 容器立即释放入口线程，
     * 结果在 traceAwareExecutor 工作线程上算完后由异步派发写回响应。
     *
     * <p>跨线程链路一致性：执行、完成回调、异常处理、最终归类全部关联同一 traceId；
     * 最终归类由 {@code TracingFilter} 注册的 AsyncListener 在异步周期结束时
     * 以最终状态/异常 CAS 落定一次，入口线程不会提前下结论。
     */
    @GetMapping("/async-nonblocking")
    public CompletableFuture<Map<String, Object>> asyncNonBlocking(
            @RequestParam(defaultValue = "success") String scenario,
            @RequestParam(defaultValue = "100") long timeoutMillis) {
        String entryTraceId = TraceContextHolder.get().traceId();
        log.info("async_release entryThread={} traceId={}",
                Thread.currentThread().getName(), entryTraceId);
        return demoService.callDependencyAsync(scenario, timeoutMillis)
                // 完成回调：无论跑在工作线程还是（已完成的）入口线程，都必须看到同一标识
                .whenComplete((result, error) -> {
                    String callbackTraceId = TraceContextHolder.isPresent()
                            ? TraceContextHolder.get().traceId() : "-";
                    log.info("async_callback traceId={} entryTraceId={} consistent={} "
                                    + "callbackThread={} outcome={}",
                            callbackTraceId, entryTraceId, entryTraceId.equals(callbackTraceId),
                            Thread.currentThread().getName(),
                            error == null ? "success" : error.getClass().getSimpleName());
                })
                .thenApply(result -> body(scenario, "async-nonblocking", result));
    }

    private Map<String, Object> body(String scenario, String stage, String result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("traceId", TraceContextHolder.get().traceId());
        map.put("scenario", scenario);
        map.put("stage", stage);
        map.put("result", result);
        map.put("worker", Thread.currentThread().getName());
        return map;
    }
}
