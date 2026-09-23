package com.github.h3yow.traceobservabilityjava.trace;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 包装任意 {@link ExecutorService}，在"提交线程 → 工作线程"之间传播追踪上下文。
 *
 * 防泄漏规则：
 * <ul>
 *   <li>提交任务时（调用方线程）快照当前 {@link TraceContext} 与 MDC 副本；</li>
 *   <li>工作线程执行前，先保存其自身旧上下文/MDC（池线程可能正服务于嵌套任务），
 *       再附着快照；执行结束（含异常）后恢复为旧状态；</li>
 *   <li>无上下文的任务会显式清空工作线程，保证上一个请求的痕迹绝不残留；</li>
 *   <li>{@code invokeAll/invokeAny} 对集合中的每个任务各自包装。</li>
 * </ul>
 * 复制的只是两个小不可变对象与一个小 Map，开销为纳秒级。
 */
public class ContextPropagatingExecutorService implements ExecutorService {

    private final ExecutorService delegate;

    public ContextPropagatingExecutorService(ExecutorService delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.delegate = delegate;
    }

    /** 在当前（提交）线程捕获上下文快照。 */
    public static ContextSnapshot capture() {
        return new ContextSnapshot(TraceContextHolder.current(), MDC.getCopyOfContextMap());
    }

    /**
     * 在当前工作线程上应用快照：先清空旧状态，再附着快照中的上下文与 MDC。
     * 调用方应在任务结束后再次 {@link #apply(ContextSnapshot)} 恢复先前快照。
     */
    public static void apply(ContextSnapshot snapshot) {
        TraceContextHolder.clear();
        MDC.clear();
        if (snapshot.traceContext() != null) {
            TraceContextHolder.push(snapshot.traceContext());
        }
        if (snapshot.mdc() != null) {
            MDC.setContextMap(snapshot.mdc());
        }
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command, capture()));
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(wrap(task, capture()));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(wrap(task, capture()), result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(wrap(task, capture()));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
            throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks, capture()));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapAll(tasks, capture()), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        return delegate.invokeAny(wrapAll(tasks, capture()));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
        return delegate.invokeAny(wrapAll(tasks, capture()), timeout, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    private static <T> List<Callable<T>> wrapAll(Collection<? extends Callable<T>> tasks,
                                                 ContextSnapshot snapshot) {
        List<Callable<T>> wrapped = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            wrapped.add(wrap(task, snapshot));
        }
        return wrapped;
    }

    private static Runnable wrap(Runnable command, ContextSnapshot snapshot) {
        return () -> runWithSnapshot(command, snapshot);
    }

    private static <T> Callable<T> wrap(Callable<T> task, ContextSnapshot snapshot) {
        return () -> callWithSnapshot(task, snapshot);
    }

    private static void runWithSnapshot(Runnable command, ContextSnapshot snapshot) {
        ContextSnapshot previous = capture();
        apply(snapshot);
        try {
            command.run();
        } finally {
            apply(previous);
        }
    }

    private static <T> T callWithSnapshot(Callable<T> task, ContextSnapshot snapshot) throws Exception {
        ContextSnapshot previous = capture();
        apply(snapshot);
        try {
            return task.call();
        } finally {
            apply(previous);
        }
    }

    /** 提交时刻的上下文不可变快照。 */
    public record ContextSnapshot(TraceContext traceContext, Map<String, String> mdc) {
    }
}
