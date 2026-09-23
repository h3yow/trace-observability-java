package com.github.h3yow.traceobservabilityjava.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Logback 内存环形缓冲 appender：把每条日志转为结构化 JSON 风格 Map 保存在进程内，
 * 可通过 traceId 直接取回，验证"同一请求的日志都指向同一条链路"。
 *
 * 线程安全：单槽写入用 synchronized，读操作返回快照副本。
 * 容量淘汰：超出容量后覆盖最旧记录（环形），不产生无限增长。
 */
public class InMemoryLogAppender extends AppenderBase<ILoggingEvent> {

    private static volatile InMemoryLogAppender INSTANCE;

    private final ThrowableProxyConverter throwableConverter = new ThrowableProxyConverter();

    private volatile int capacity = 2000;
    private LogEntry[] buffer = new LogEntry[capacity];
    private int writeIndex;
    private int size;

    private final AtomicInteger dropped = new AtomicInteger();

    public InMemoryLogAppender() {
        setName("IN_MEMORY");
        INSTANCE = this;
    }

    public static InMemoryLogAppender getInstance() {
        return INSTANCE;
    }

    @Override
    public void start() {
        throwableConverter.setContext(getContext());
        throwableConverter.start();
        super.start();
    }

    public synchronized void setCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        List<LogEntry> old = newestFirst(Math.min(size, capacity));
        this.capacity = capacity;
        this.buffer = new LogEntry[capacity];
        this.writeIndex = 0;
        this.size = 0;
        // newestFirst 返回最新在前，重放时从最旧开始写，逆序遍历
        for (int i = old.size() - 1; i >= 0; i--) {
            buffer[writeIndex] = old.get(i);
            writeIndex = (writeIndex + 1) % capacity;
            size++;
        }
    }

    @Override
    protected synchronized void append(ILoggingEvent event) {
        buffer[writeIndex] = toEntry(event);
        writeIndex = (writeIndex + 1) % capacity;
        if (size < capacity) {
            size++;
        } else {
            dropped.incrementAndGet();
        }
    }

    /** 返回属于指定 traceId 的日志（时间正序）。 */
    public synchronized List<Map<String, Object>> findByTraceId(String traceId) {
        if (traceId == null) {
            return List.of();
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        for (LogEntry entry : oldestFirst()) {
            if (traceId.equals(entry.fields.get("traceId"))) {
                matched.add(entry.toMap());
            }
        }
        return matched;
    }

    /** 返回最近 limit 条日志（最新在前）。 */
    public synchronized List<Map<String, Object>> recent(int limit) {
        int max = Math.max(1, limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (LogEntry entry : newestFirst(max)) {
            result.add(entry.toMap());
        }
        return result;
    }

    public synchronized void reset() {
        java.util.Arrays.fill(buffer, null);
        writeIndex = 0;
        size = 0;
        dropped.set(0);
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }

    public int droppedCount() {
        return dropped.get();
    }

    private List<LogEntry> oldestFirst() {
        List<LogEntry> ordered = newestFirst(size);
        java.util.Collections.reverse(ordered);
        return ordered;
    }

    private List<LogEntry> newestFirst(int limit) {
        List<LogEntry> result = new ArrayList<>();
        int count = Math.min(limit, size);
        for (int i = 0; i < count; i++) {
            int idx = Math.floorMod(writeIndex - 1 - i, capacity);
            LogEntry entry = buffer[idx];
            if (entry != null) {
                result.add(entry);
            }
        }
        return result;
    }

    private LogEntry toEntry(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        fields.put("level", event.getLevel().toString());
        fields.put("logger", event.getLoggerName());
        fields.put("thread", event.getThreadName());
        fields.put("message", event.getFormattedMessage());

        // MDC 中的 traceId/spanId 使日志与链路天然关联
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            String traceId = mdc.get(TraceMdcKeys.TRACE_ID);
            String spanId = mdc.get(TraceMdcKeys.SPAN_ID);
            if (traceId != null) {
                fields.put("traceId", traceId);
            }
            if (spanId != null) {
                fields.put("spanId", spanId);
            }
        }

        if (event.getThrowableProxy() != null) {
            Throwable thrown = event.getThrowableProxy() instanceof ThrowableProxy tp
                    ? tp.getThrowable() : null;
            if (thrown != null) {
                fields.put("errorType", thrown.getClass().getName());
                fields.put("errorMessage", thrown.getMessage());
            }
            String stack = throwableConverter.convert(event);
            if (stack != null && !stack.isBlank()) {
                fields.put("stackTrace", stack.trim());
            }
        }
        return new LogEntry(fields);
    }

    private record LogEntry(Map<String, Object> fields) {
        private Map<String, Object> toMap() {
            return new LinkedHashMap<>(fields);
        }
    }

    private static final class TraceMdcKeys {
        private static final String TRACE_ID = "traceId";
        private static final String SPAN_ID = "spanId";
    }
}
