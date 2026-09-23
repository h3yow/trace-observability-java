package com.github.h3yow.traceobservabilityjava.trace;

import java.util.concurrent.Executor;

/**
 * 包装普通 Executor：提交任务时快照当前 TraceContext / Observation / MDC，
 * 工作线程运行前恢复、运行后（含异常）还原现场，杜绝线程复用泄漏。
 */
public final class ContextPropagatingExecutor implements Executor {

    private final Executor delegate;

    public ContextPropagatingExecutor(Executor delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(wrap(command));
    }

    /** 在调用方线程捕获快照，把任务包装成"恢复-执行-清理"的安全形式。 */
    public static Runnable wrap(Runnable task) {
        return ContextSnapshot.capture().wrap(task);
    }
}
