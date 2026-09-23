package com.github.h3yow.traceobservabilityjava.trace;

import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.concurrent.ListenableFuture;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

/**
 * 同时满足 Spring MVC 异步契约（{@code AsyncTaskExecutor}）与
 * {@code ExecutorService} 用法的上下文传播线程池。
 *
 * 通过 {@link TaskDecorator} 在任务提交时快照 TraceContext/MDC、
 * 在工作线程执行时附着、结束后恢复，所有提交入口（execute/submit/CompletableFuture）
 * 都会经过该装饰器，因此传播规则只有一处实现。
 */
public class ContextPropagatingTaskExecutor extends ThreadPoolTaskExecutor {

    public ContextPropagatingTaskExecutor(int poolSize, String threadNamePrefix) {
        setCorePoolSize(poolSize);
        setMaxPoolSize(poolSize);
        setThreadNamePrefix(threadNamePrefix);
        setWaitForTasksToCompleteOnShutdown(false);
        setTaskDecorator(contextPropagatingDecorator());
        initialize();
    }

    /**
     * 提交线程（decorator 被调用的线程）上捕获快照；
     * 返回的 Runnable 在工作线程上执行，附着快照并在 finally 中恢复工作线程旧状态。
     */
    public static TaskDecorator contextPropagatingDecorator() {
        return runnable -> {
            ContextPropagatingExecutorService.ContextSnapshot snapshot =
                    ContextPropagatingExecutorService.capture();
            return () -> {
                ContextPropagatingExecutorService.ContextSnapshot previous =
                        ContextPropagatingExecutorService.capture();
                ContextPropagatingExecutorService.apply(snapshot);
                try {
                    runnable.run();
                } finally {
                    ContextPropagatingExecutorService.apply(previous);
                }
            };
        };
    }

    // 以下显式重写仅为让"传播对所有提交入口生效"在代码审查时一目了然；
    // ThreadPoolTaskExecutor 的默认 submit 实现本身也会经过 TaskDecorator。
    @Override
    public void execute(Runnable task) {
        super.execute(task);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return super.submit(task);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return super.submit(task);
    }

    @Override
    public CompletableFuture<Void> submitCompletable(Runnable task) {
        return super.submitCompletable(task);
    }

    @Override
    public <T> CompletableFuture<T> submitCompletable(Callable<T> task) {
        return super.submitCompletable(task);
    }

    @Override
    public ListenableFuture<?> submitListenable(Runnable task) {
        return super.submitListenable(task);
    }

    @Override
    public <T> ListenableFuture<T> submitListenable(Callable<T> task) {
        return super.submitListenable(task);
    }
}
