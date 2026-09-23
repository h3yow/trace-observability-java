package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.service.DemoService;
import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
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
