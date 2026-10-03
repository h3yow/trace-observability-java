package com.github.h3yow.traceobservabilityjava.observation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 进程内观测注册表：保存每个请求的 {@link Observation} 与聚合 {@link MetricsStore}。
 *
 * <p>完全本地、无外部后端；通过 /internal/metrics 与测试可直接读取。
 * 为避免内存无限增长，观测条目超过 {@code maxObservations} 时淘汰最老条目
 * （指标计数不受淘汰影响）。
 */
public final class ObservationRegistry {

    private static final Logger log = LoggerFactory.getLogger(ObservationRegistry.class);

    private final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final MetricsStore metrics;
    private final int maxObservations;

    public ObservationRegistry(int maxObservations) {
        this(maxObservations, 2048);
    }

    public ObservationRegistry(int maxObservations, int latencySampleCapacity) {
        this.maxObservations = Math.max(10, maxObservations);
        this.metrics = new MetricsStore(latencySampleCapacity);
    }

    /** 取得或创建某请求的 Observation。 */
    public Observation getOrCreate(String traceId) {
        Observation created = new Observation(traceId);
        Observation existing = observations.putIfAbsent(traceId, created);
        if (existing == null) {
            insertionOrder.add(traceId);
            evictIfNeeded();
            return created;
        }
        return existing;
    }

    private void evictIfNeeded() {
        while (insertionOrder.size() > maxObservations) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                break;
            }
            Observation removed = observations.remove(oldest);
            // 已落定的指标已经计入 MetricsStore，淘汰明细不影响计数
            if (removed != null && log.isDebugEnabled()) {
                log.debug("evict observation traceId={} settled={}", oldest, removed.isSettled());
            }
        }
    }

    /**
     * 请求最终归类首次落定时调用；CAS 保证每个请求只进来计数一次。
     */
    public void onSettled(Observation observation, long durationNanos) {
        metrics.record(observation.finalOutcome(), durationNanos);
    }

    public Observation get(String traceId) {
        return observations.get(traceId);
    }

    public Collection<Observation> all() {
        return observations.values();
    }

    public MetricsStore metrics() {
        return metrics;
    }

    /** 仅测试使用：清空全部状态。 */
    public void reset() {
        observations.clear();
        insertionOrder.clear();
        metrics.reset();
    }
}
