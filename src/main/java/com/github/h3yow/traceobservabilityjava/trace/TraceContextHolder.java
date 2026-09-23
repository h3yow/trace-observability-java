package com.github.h3yow.traceobservabilityjava.trace;

/**
 * 基于 ThreadLocal 的追踪上下文持有者。
 * 关键约束：线程池复用时必须保证 clear()，避免上下文泄漏给下一个任务。
 */
public final class TraceContextHolder {

    private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

    private TraceContextHolder() {}

    public static void set(TraceContext context) {
        CURRENT.set(context);
    }

    public static TraceContext get() {
        return CURRENT.get();
    }

    public static boolean isPresent() {
        return CURRENT.get() != null;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
