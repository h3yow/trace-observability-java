package com.github.h3yow.traceobservabilityjava.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存日志缓冲：结构化字段、按 traceId 过滤、环形淘汰。
 */
class InMemoryLogAppenderTest {

    private InMemoryLogAppender appender;
    private LoggerContext context;

    @BeforeEach
    void setUp() {
        context = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender = new InMemoryLogAppender();
        appender.setContext(context);
        appender.setCapacity(8);
        appender.start();
    }

    @AfterEach
    void tearDown() {
        appender.stop();
        MDC.clear();
    }

    private void emit(String level, String message, String traceId, String spanId) {
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        if (spanId != null) {
            MDC.put("spanId", spanId);
        }
        LoggingEvent event = new LoggingEvent(
                this.getClass().getName(),
                context.getLogger("test"),
                Level.toLevel(level),
                message,
                null, null);
        appender.doAppend(event);
        MDC.clear();
    }

    private void emit(String level, String message, String traceId) {
        emit(level, message, traceId, null);
    }

    @Test
    @DisplayName("按 traceId 取回的日志全部带相同 traceId 且含结构化字段")
    void filterByTraceId() {
        emit("INFO", "a-1", "tttttttttttttttttttttttttttttt01", "span-1");
        emit("WARN", "other", "tttttttttttttttttttttttttttttt02", "span-2");
        emit("INFO", "a-2", "tttttttttttttttttttttttttttttt01", "span-3");

        List<Map<String, Object>> logs = appender
                .findByTraceId("tttttttttttttttttttttttttttttt01");
        assertEquals(2, logs.size());
        for (Map<String, Object> entry : logs) {
            assertEquals("tttttttttttttttttttttttttttttt01", entry.get("traceId"));
            assertTrue(entry.containsKey("timestamp"));
            assertTrue(entry.containsKey("level"));
            assertTrue(entry.containsKey("thread"));
            assertTrue(entry.containsKey("message"));
        }
        assertEquals("a-1", logs.get(0).get("message"), "时间正序");
        assertEquals("a-2", logs.get(1).get("message"));
        System.out.printf("[LogAppenderTest] 按 traceId 过滤 | 输入=3 条(2 条同链路) | "
                + "取回=%d 条且字段完整%n", logs.size());
    }

    @Test
    @DisplayName("超出容量后环形淘汰最旧日志，不无限增长")
    void ringBufferEvictsOldest() {
        for (int i = 0; i < 20; i++) {
            emit("INFO", "m" + i, String.format("%032d", i), null);
        }
        assertEquals(8, appender.size());
        // 最旧的 m0 已淘汰，最新的 m19 仍在
        assertTrue(appender.findByTraceId(String.format("%032d", 0)).isEmpty(),
                "最旧日志必须被淘汰");
        assertFalse(appender.findByTraceId(String.format("%032d", 19)).isEmpty(),
                "最新日志必须保留");
        List<Map<String, Object>> recent = appender.recent(1);
        assertEquals("m19", recent.get(0).get("message"));
        System.out.printf("[LogAppenderTest] 环形淘汰 | 容量=8 写入=20 | 保留=%d dropped=%d%n",
                appender.size(), appender.droppedCount());
    }

    @Test
    @DisplayName("reset 后缓冲清空")
    void resetClears() {
        emit("INFO", "x", String.format("%032d", 1), null);
        appender.reset();
        assertEquals(0, appender.size());
        assertTrue(appender.findByTraceId(String.format("%032d", 1)).isEmpty());
    }
}
