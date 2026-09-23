package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.domain.BusinessRejectionException;
import com.github.h3yow.traceobservabilityjava.domain.DependencyService;
import com.github.h3yow.traceobservabilityjava.domain.DependencyTimeoutException;
import com.github.h3yow.traceobservabilityjava.trace.TraceContext;
import com.github.h3yow.traceobservabilityjava.trace.TraceContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 演示接口，覆盖全部归类与传播场景：
 * <ul>
 *   <li>GET  /demo/ping       正常完成</li>
 *   <li>GET  /demo/reject     业务拒绝（422）</li>
 *   <li>GET  /demo/timeout    依赖超时（504）</li>
 *   <li>GET  /demo/boom       未预期异常（500）</li>
 *   <li>GET  /demo/retry      前 N 次依赖失败后重试成功 → 最终 SUCCESS（失败只留事件）</li>
 *   <li>GET  /demo/async      线程池异步处理，traceId 跨线程保持</li>
 *   <li>POST /demo/background 提交即返回的后台任务，后台日志同 traceId</li>
 * </ul>
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DependencyService dependencyService;
    private final Executor taskExecutor;

    public DemoController(DependencyService dependencyService,
                          @Qualifier("taskExecutor") Executor taskExecutor) {
        this.dependencyService = dependencyService;
        this.taskExecutor = taskExecutor;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        TraceContext ctx = TraceContextHolder.current();
        log.info("处理 ping 请求");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pong", true);
        body.put("traceId", ctx == null ? null : ctx.traceId());
        return body;
    }

    @GetMapping("/reject")
    public Map<String, Object> reject(@RequestParam(defaultValue = "余额不足") String reason) {
        log.info("业务校验未通过 reason={}", reason);
        throw new BusinessRejectionException(reason);
    }

    @GetMapping("/timeout")
    public Map<String, Object> timeout(@RequestParam(defaultValue = "300") long delayMillis,
                                       @RequestParam(defaultValue = "200") long timeoutMillis) {
        log.info("发起可能超时的依赖调用 delayMillis={} timeoutMillis={}", delayMillis, timeoutMillis);
        String result = dependencyService.call("payment", delayMillis, timeoutMillis, 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        return body;
    }

    @GetMapping("/boom")
    public Map<String, Object> boom() {
        log.error("即将抛出未预期异常");
        throw new IllegalStateException("模拟一个没人预料到的故障");
    }

    /**
     * 重试场景：前 failAttempts 次依赖调用超时（只产生 DEPENDENCY_TIMEOUT 事件），
     * 最后一次缩短模拟延迟后成功。请求最终唯一归类为 SUCCESS。
     */
    @GetMapping("/retry")
    public Map<String, Object> retry(@RequestParam(defaultValue = "2") int failAttempts,
                                     @RequestParam(defaultValue = "10") long attemptDelayMillis,
                                     @RequestParam(defaultValue = "200") long timeoutMillis) {
        log.info("开始重试流程 failAttempts={}", failAttempts);
        String result = null;
        int total = failAttempts + 1;
        for (int attempt = 1; attempt <= total; attempt++) {
            try {
                long delay = attempt <= failAttempts ? timeoutMillis + attemptDelayMillis : attemptDelayMillis;
                result = dependencyService.call("payment", delay, timeoutMillis, attempt);
                log.info("第 {} 次尝试成功", attempt);
                break;
            } catch (DependencyTimeoutException ex) {
                log.warn("第 {} 次尝试超时，准备重试", attempt);
                if (attempt == total) {
                    throw ex;
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", result);
        body.put("attempts", total);
        return body;
    }

    /** 异步场景：业务在工作线程中执行依赖调用，traceId 由包装线程池自动传播。 */
    @GetMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        TraceContext ctx = TraceContextHolder.current();
        String traceId = ctx == null ? null : ctx.traceId();
        log.info("提交异步任务 traceId={}", traceId);
        return CompletableFuture.supplyAsync(() -> {
            TraceContext workerCtx = TraceContextHolder.current();
            log.info("工作线程开始处理异步任务 traceId={} thread={}",
                    workerCtx == null ? null : workerCtx.traceId(), Thread.currentThread().getName());
            String result = dependencyService.call("asyncPayment", 20, 500, 1);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("result", result);
            body.put("traceId", workerCtx == null ? null : workerCtx.traceId());
            body.put("thread", Thread.currentThread().getName());
            return body;
        }, taskExecutor);
    }

    /** 后台场景：提交后立即返回 202，任务在同一条 traceId 下继续执行。 */
    @PostMapping("/background")
    public Map<String, Object> background() {
        TraceContext ctx = TraceContextHolder.current();
        String traceId = ctx == null ? null : ctx.traceId();
        log.info("提交后台任务 traceId={}", traceId);
        taskExecutor.execute(this::runInBackground);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", true);
        body.put("traceId", traceId);
        return body;
    }

    private void runInBackground() {
        TraceContext ctx = TraceContextHolder.current();
        log.info("后台任务执行中 traceId={} thread={}",
                ctx == null ? null : ctx.traceId(), Thread.currentThread().getName());
        try {
            Thread.sleep(30);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        dependencyService.call("backgroundJob", 10, 500, 1);
        log.info("后台任务完成 traceId={}", ctx == null ? null : ctx.traceId());
    }
}
