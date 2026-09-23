package com.github.h3yow.traceobservabilityjava.web;

import com.github.h3yow.traceobservabilityjava.logging.InMemoryLogAppender;
import com.github.h3yow.traceobservabilityjava.observability.MetricsSnapshot;
import com.github.h3yow.traceobservabilityjava.observability.ObservationStore;
import com.github.h3yow.traceobservabilityjava.observability.RequestObservation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 本地验证端点：无需任何外部后端，在进程内直接取回链路、指标与结构化日志。
 *
 * <ul>
 *   <li>GET  /observability/traces/{id}  查看单条链路（最终归类、耗时、全部 span 事件）</li>
 *   <li>GET  /observability/traces       最近已结案链路</li>
 *   <li>GET  /observability/metrics      聚合计数、成功率、延迟分位数</li>
 *   <li>GET  /observability/logs/{id}    属于该 traceId 的结构化日志</li>
 *   <li>POST /observability/reset        清空采集结果（测试隔离用）</li>
 * </ul>
 */
@RestController
@RequestMapping("/observability")
public class ObservabilityQueryController {

    private final ObservationStore store;

    public ObservabilityQueryController(ObservationStore store) {
        this.store = store;
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<RequestObservation> trace(@PathVariable String traceId) {
        RequestObservation observation = store.get(traceId);
        return observation == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).build()
                : ResponseEntity.ok(observation);
    }

    @GetMapping("/traces")
    public List<RequestObservation> traces(@RequestParam(defaultValue = "50") int limit) {
        return store.recent(limit);
    }

    @GetMapping("/metrics")
    public MetricsSnapshot metrics() {
        return store.snapshot();
    }

    @GetMapping("/logs/{traceId}")
    public List<Map<String, Object>> logs(@PathVariable String traceId) {
        InMemoryLogAppender appender = InMemoryLogAppender.getInstance();
        return appender == null ? List.of() : appender.findByTraceId(traceId);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        store.reset();
        InMemoryLogAppender appender = InMemoryLogAppender.getInstance();
        if (appender != null) {
            appender.reset();
        }
        return Map.of("reset", true);
    }
}
