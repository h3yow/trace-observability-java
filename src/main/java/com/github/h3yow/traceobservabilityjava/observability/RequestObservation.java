package com.github.h3yow.traceobservabilityjava.observability;

import java.util.List;

/**
 * 一条请求链路的只读观察结果。
 */
public interface RequestObservation {

    String traceId();

    String method();

    String path();

    int status();

    OutcomeCategory outcome();

    long elapsedNanos();

    boolean upstreamProvided();

    List<SpanEvent> events();

    String errorType();

    long startMillis();
}
