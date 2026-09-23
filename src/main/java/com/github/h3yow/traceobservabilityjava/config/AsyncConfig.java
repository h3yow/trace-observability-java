package com.github.h3yow.traceobservabilityjava.config;

import com.github.h3yow.traceobservabilityjava.trace.ContextPropagatingExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 暴露一个经过 TraceContext 快照包装的线程池 Bean，供异步/后台任务使用。
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "traceAwareExecutor", destroyMethod = "shutdown")
    public ExecutorService traceAwareExecutor(ObservabilityProperties props) {
        int size = Math.max(1, props.getAsyncPoolSize());
        ExecutorService pool = new ThreadPoolExecutor(
                size, size, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        return new ContextPropagatingExecutorService(pool);
    }
}
