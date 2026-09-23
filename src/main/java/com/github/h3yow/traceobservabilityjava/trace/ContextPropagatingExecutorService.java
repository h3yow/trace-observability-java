package com.github.h3yow.traceobservabilityjava.trace;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对 ExecutorService 的透明包装。
 *
 * <p>{@link AbstractExecutorService} 的 submit/invokeAll/invokeAny 最终都走
 * {@link #execute(Runnable)}，因此只要在 execute 一处做快照包装，
 * 所有提交方式（含 {@code CompletableFuture.supplyAsync(..., this)}）都会传播上下文。
 */
public final class ContextPropagatingExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    public ContextPropagatingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        // 在【提交方线程】捕获，而不是在工作线程读取，这是传播正确的关键
        delegate.execute(ContextSnapshot.capture().wrap(command));
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
}
