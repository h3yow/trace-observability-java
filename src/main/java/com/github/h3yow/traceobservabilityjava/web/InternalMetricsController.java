package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.observation.MetricsStore;
import com.github.h3yow.traceobservabilityjava.observation.Observation;
import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地采集结果读取端点（全部来自进程内内存，无外部后端）：
 * <ul>
 *   <li>GET /internal/metrics —— 按归类的计数与累计/平均耗时；total 必须等于各分类之和</li>
 *   <li>GET /internal/observations —— 每个请求的最终归类、HTTP 状态、尝试明细</li>
 *   <li>GET /internal/observation?traceId=... —— 单条链路的可解释视图</li>
 * </ul>
 */
@RestController
@RequestMapping("/internal")
public class InternalMetricsController {

    private final ObservationRegistry registry;

    public InternalMetricsController(ObservationRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        MetricsStore m = registry.metrics();
        Map<String, Object> byOutcome = new LinkedHashMap<>();
        for (Outcome outcome : Outcome.values()) {
            MetricsStore.LatencyStats stats = m.latencyStats(outcome);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("count", stats.count());
            one.put("totalNanos", stats.totalNanos());
            one.put("totalMillis", nanosToMillis(stats.totalNanos()));
            one.put("avgNanos", stats.avgNanos());
            one.put("p50Nanos", stats.p50Nanos());
            one.put("p95Nanos", stats.p95Nanos());
            one.put("p99Nanos", stats.p99Nanos());
            one.put("p50Millis", nanosToMillis(stats.p50Nanos()));
            one.put("p95Millis", nanosToMillis(stats.p95Nanos()));
            one.put("p99Millis", nanosToMillis(stats.p99Nanos()));
            one.put("latencySampleSize", stats.sampleSize());
            byOutcome.put(outcome.name(), one);
        }
        MetricsStore.LatencyStats overall = m.overallLatencyStats();
        Map<String, Object> overallView = new LinkedHashMap<>();
        overallView.put("count", overall.count());
        overallView.put("avgNanos", overall.avgNanos());
        overallView.put("p50Nanos", overall.p50Nanos());
        overallView.put("p95Nanos", overall.p95Nanos());
        overallView.put("p99Nanos", overall.p99Nanos());
        overallView.put("p50Millis", nanosToMillis(overall.p50Nanos()));
        overallView.put("p95Millis", nanosToMillis(overall.p95Nanos()));
        overallView.put("p99Millis", nanosToMillis(overall.p99Nanos()));
        overallView.put("latencySampleSize", overall.sampleSize());
        overallView.put("successRate", m.successRate());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", m.totalCount());
        result.put("successRate", m.successRate());
        result.put("reservoirCapacity", m.reservoirCapacity());
        result.put("byOutcome", byOutcome);
        result.put("overall", overallView);
        return result;
    }

    @GetMapping("/observations")
    public Map<String, Object> observations() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Observation o : registry.all()) {
            items.add(toView(o));
        }
        items.sort((a, b) -> String.valueOf(a.get("traceId")).compareTo(String.valueOf(b.get("traceId"))));
        return Map.of("count", items.size(), "observations", items);
    }

    @GetMapping("/observation")
    public Map<String, Object> observation(@RequestParam String traceId) {
        Observation o = registry.get(traceId);
        if (o == null) {
            return Map.of("found", false, "traceId", traceId);
        }
        Map<String, Object> view = toView(o);
        view.put("found", true);
        return view;
    }

    private Map<String, Object> toView(Observation o) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("traceId", o.traceId());
        view.put("settled", o.isSettled());
        view.put("finalOutcome", o.finalOutcome());
        view.put("httpStatus", o.finalHttpStatus());
        view.put("durationNanos", o.finalDurationNanos());
        view.put("durationMillis", nanosToMillis(o.finalDurationNanos()));
        view.put("attemptCount", o.attemptCount());
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (Observation.Attempt a : o.attempts()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("attemptId", a.attemptId());
            am.put("outcome", a.outcome());
            am.put("durationNanos", a.durationNanos());
            attempts.add(am);
        }
        view.put("attempts", attempts);
        return view;
    }

    private static double nanosToMillis(long nanos) {
        if (nanos < 0) {
            return -1d;
        }
        return nanos / 1_000_000d;
    }
}
