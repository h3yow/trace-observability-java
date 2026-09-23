package com.github.h3yow.traceobservabilityjava.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 可配置项，前缀 observability。
 */
@ConfigurationProperties(prefix = "observability")
public class ObservabilityProperties {

    private boolean enabled = true;
    private int storeCapacity = 10_000;
    private int logBufferCapacity = 2_000;
    private int asyncPoolSize = 4;
    private long dependencyTimeoutMillis = 200;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStoreCapacity() {
        return storeCapacity;
    }

    public void setStoreCapacity(int storeCapacity) {
        this.storeCapacity = storeCapacity;
    }

    public int getLogBufferCapacity() {
        return logBufferCapacity;
    }

    public void setLogBufferCapacity(int logBufferCapacity) {
        this.logBufferCapacity = logBufferCapacity;
    }

    public int getAsyncPoolSize() {
        return asyncPoolSize;
    }

    public void setAsyncPoolSize(int asyncPoolSize) {
        this.asyncPoolSize = asyncPoolSize;
    }

    public long getDependencyTimeoutMillis() {
        return dependencyTimeoutMillis;
    }

    public void setDependencyTimeoutMillis(long dependencyTimeoutMillis) {
        this.dependencyTimeoutMillis = dependencyTimeoutMillis;
    }
}
