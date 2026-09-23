package com.github.h3yow.traceobservabilityjava.trace;

import org.slf4j.MDC;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 基于 ThreadLocal 栈的追踪上下文持有器。
 *
 * 设计要点：
 * <ul>
 *   <li>用栈而非单值，支持嵌套调用（controller → service → 重试）逐层出入；</li>
 *   <li>push/pop 同步维护 MDC 的 traceId/spanId，使所有日志自动带链路标识；</li>
 *   <li>{@link #attach} 返回 {@link Scope}，try-with-resources 保证异常路径也清理；</li>
 *   <li>工作线程执行任务前必须确认栈为空，执行后必须恢复，杜绝线程复用泄漏。</li>
 * </ul>
 */
public final class TraceContextHolder {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";

    private static final ThreadLocal<Deque<TraceContext>> STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private TraceContextHolder() {
    }

    /** 压入一层上下文（根 span 或子 span），并刷新 MDC。 */
    public static void push(TraceContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        STACK.get().push(context);
        MDC.put(MDC_TRACE_ID, context.traceId());
        MDC.put(MDC_SPAN_ID, context.spanId());
    }

    /** 当前栈顶上下文；当前线程无上下文时返回 null。 */
    public static TraceContext current() {
        return STACK.get().peek();
    }

    /** 弹出栈顶并把 MDC 恢复为上一层；栈空时彻底清除 MDC。 */
    public static TraceContext pop() {
        Deque<TraceContext> stack = STACK.get();
        TraceContext popped = stack.pollFirst();
        TraceContext next = stack.peek();
        if (next != null) {
            MDC.put(MDC_TRACE_ID, next.traceId());
            MDC.put(MDC_SPAN_ID, next.spanId());
        } else {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
            // 避免线程池中长期持有空 Deque 对象
            STACK.remove();
        }
        return popped;
    }

    /** 当前线程是否没有任何追踪上下文（线程泄漏自检用）。 */
    public static boolean isEmpty() {
        return STACK.get().isEmpty();
    }

    /** 无条件清空当前线程的上下文与 MDC（请求边界兜底用）。 */
    public static void clear() {
        STACK.remove();
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_SPAN_ID);
    }

    /**
     * 附着上下文并返回作用域，close 时弹出。
     * 典型用法：{@code try (var ignored = TraceContextHolder.attach(ctx)) { ... }}
     */
    public static Scope attach(TraceContext context) {
        push(context);
        return new Scope();
    }

    /** 自动关闭的上下文作用域，重复关闭安全。 */
    public static final class Scope implements AutoCloseable {

        private boolean closed;

        private Scope() {
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                pop();
            }
        }
    }
}
