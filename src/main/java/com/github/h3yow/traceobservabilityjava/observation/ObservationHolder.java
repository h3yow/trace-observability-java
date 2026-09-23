package com.github.h3yow.traceobservabilityjava.observation;

/**
 * 当前线程的请求观测句柄，与 TraceContext 一起被快照传播到线程池。
 */
public final class ObservationHolder {

    private static final ThreadLocal<ObservationContext> CURRENT = new ThreadLocal<>();

    private ObservationHolder() {}

    public static void set(ObservationContext context) {
        CURRENT.set(context);
    }

    public static ObservationContext get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
