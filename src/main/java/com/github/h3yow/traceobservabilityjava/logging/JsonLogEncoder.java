package com.github.h3yow.traceobservabilityjava.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

/**
 * 极简结构化 JSON 日志编码器（仅依赖 logback，spring-boot-starter-web 已传递引入）。
 *
 * <p>每行一个 JSON：timestamp / level / logger / thread / message，
 * 并平铺 MDC 中的 traceId、spanId、requestId、traceSource 等字段，
 * 从而把"同一条请求"的日志在本地（控制台/文件）即可关联起来。
 */
public class JsonLogEncoder extends EncoderBase<ILoggingEvent> {

    private static final byte[] EMPTY = new byte[0];

    @Override
    public byte[] headerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] footerBytes() {
        return EMPTY;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendString(sb, "timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        sb.append(',');
        appendString(sb, "level", event.getLevel().toString());
        sb.append(',');
        appendString(sb, "logger", event.getLoggerName());
        sb.append(',');
        appendString(sb, "thread", event.getThreadName());
        sb.append(',');
        appendString(sb, "message", event.getFormattedMessage());

        if (event.getMDCPropertyMap() != null) {
            for (Map.Entry<String, String> e : event.getMDCPropertyMap().entrySet()) {
                sb.append(',');
                appendString(sb, e.getKey(), e.getValue());
            }
        }

        if (event.getThrowableProxy() != null) {
            String stack = ThrowableProxyUtil.asString(event.getThrowableProxy());
            sb.append(',');
            appendString(sb, "stack", stack);
        }
        sb.append('}').append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendString(StringBuilder sb, String key, String value) {
        sb.append('"').append(escape(key)).append('"').append(':')
                .append('"').append(escape(value == null ? "" : value)).append('"');
    }

    private static String escape(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
