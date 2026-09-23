package com.github.h3yow.traceobservabilityjava.observability;

/**
 * 指标快照，全部在进程内存中聚合。
 */
public record MetricsSnapshot(long totalRequests,
                              long success,
                              long businessRejected,
                              long dependencyTimeout,
                              long unexpectedError,
                              long activeRequests,
                              double successRate,
                              CategoryLatency latency) {

    public record CategoryLatency(long count,
                                  double avgNanos,
                                  long minNanos,
                                  long maxNanos,
                                  long p50Nanos,
                                  long p95Nanos,
                                  long p99Nanos) {
        public static final CategoryLatency EMPTY = new CategoryLatency(0, 0, 0, 0, 0, 0, 0);
    }
}
