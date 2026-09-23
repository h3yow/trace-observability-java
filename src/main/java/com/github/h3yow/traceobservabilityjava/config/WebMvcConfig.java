package com.github.h3yow.traceobservabilityjava.config;

import com.github.h3yow.traceobservabilityjava.trace.AsyncTracingInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册异步识别拦截器。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AsyncTracingInterceptor());
    }
}
