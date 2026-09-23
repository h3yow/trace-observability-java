package com.github.h3yow.traceobservabilityjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可观测性配置项（前缀 observability）。
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private String traceHeader = "X-Trace-Id";
    private String traceIdPattern = "^[0-9a-fA-F]{16,32}$";
    private int traceIdLength = 32;
    private int asyncPoolSize = 8;
    private int maxObservations = 1000;

    public String getTraceHeader() { return traceHeader; }
    public void setTraceHeader(String traceHeader) { this.traceHeader = traceHeader; }

    public String getTraceIdPattern() { return traceIdPattern; }
    public void setTraceIdPattern(String traceIdPattern) { this.traceIdPattern = traceIdPattern; }

    public int getTraceIdLength() { return traceIdLength; }
    public void setTraceIdLength(int traceIdLength) { this.traceIdLength = traceIdLength; }

    public int getAsyncPoolSize() { return asyncPoolSize; }
    public void setAsyncPoolSize(int asyncPoolSize) { this.asyncPoolSize = asyncPoolSize; }

    public int getMaxObservations() { return maxObservations; }
    public void setMaxObservations(int maxObservations) { this.maxObservations = maxObservations; }
}
