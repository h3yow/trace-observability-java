package com.github.h3yow.traceobservabilityjava.config;

import com.github.h3yow.traceobservabilityjava.observability.ObservabilityProperties;
import com.github.h3yow.traceobservabilityjava.trace.ContextPropagatingTaskExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 装配：开启 {@code @Async}、注册配置属性、提供会传播追踪上下文的线程池。
 *
 * {@code taskExecutor} 同时是 Spring MVC 处理控制器 {@code CompletableFuture}
 * 的默认异步执行器，因此 Web 异步与业务显式提交共用同一传播规则。
 */
@Configuration
@EnableAsync
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean(name = "taskExecutor")
    @Primary
    public ContextPropagatingTaskExecutor taskExecutor(ObservabilityProperties properties) {
        return new ContextPropagatingTaskExecutor(
                Math.max(1, properties.getAsyncPoolSize()), "trace-async-");
    }
}
