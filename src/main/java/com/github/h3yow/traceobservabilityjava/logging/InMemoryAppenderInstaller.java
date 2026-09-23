package com.github.h3yow.traceobservabilityjava.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.github.h3yow.traceobservabilityjava.observability.ObservabilityProperties;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后以编程方式把 {@link InMemoryLogAppender} 挂到 ROOT logger。
 * 这样无需替换 Spring Boot 默认 logback 配置即可在进程内取回结构化日志；
 * 控制台输出仍由 Boot 默认 pattern（已在 application.properties 加入 traceId）负责。
 */
@Component
public class InMemoryAppenderInstaller {

    private final ObservabilityProperties properties;

    public InMemoryAppenderInstaller(ObservabilityProperties properties) {
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void install() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        synchronized (root) {
            if (root.getAppender("IN_MEMORY") != null) {
                return;
            }
            InMemoryLogAppender appender = new InMemoryLogAppender();
            appender.setContext(context);
            appender.setCapacity(properties.getLogBufferCapacity());
            appender.start();
            root.addAppender(appender);
        }
    }
}
