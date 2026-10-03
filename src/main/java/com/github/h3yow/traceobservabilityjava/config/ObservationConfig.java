package com.github.h3yow.traceobservabilityjava.config;

import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
import com.github.h3yow.traceobservabilityjava.observation.OutcomePolicy;
import com.github.h3yow.traceobservabilityjava.trace.TracePropagator;
import com.github.h3yow.traceobservabilityjava.trace.TracingFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 可观测性相关 Bean 装配。
 */
@Configuration
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservationConfig {

    @Bean
    public TracePropagator tracePropagator(ObservabilityProperties props) {
        return new TracePropagator(props.getTraceHeader(), props.getTraceIdPattern(),
                props.getTraceIdLength());
    }

    @Bean
    public OutcomePolicy outcomePolicy() {
        return new OutcomePolicy();
    }

    @Bean
    public ObservationRegistry observationRegistry(ObservabilityProperties props) {
        return new ObservationRegistry(props.getMaxObservations(), props.getLatencyReservoirSize());
    }

    @Bean
    public FilterRegistrationBean<TracingFilter> tracingFilterRegistration(
            TracePropagator propagator, ObservationRegistry registry, OutcomePolicy policy) {
        TracingFilter filter = new TracingFilter(propagator, registry, policy);
        FilterRegistrationBean<TracingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
