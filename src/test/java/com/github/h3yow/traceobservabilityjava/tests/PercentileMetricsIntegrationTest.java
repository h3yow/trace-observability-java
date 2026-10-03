package com.github.h3yow.traceobservabilityjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.h3yow.traceobservabilityjava.observation.MetricsStore;
import com.github.h3yow.traceobservabilityjava.observation.ObservationRegistry;
import com.github.h3yow.traceobservabilityjava.observation.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分位数指标：端点视图（含空数据不 NaN）、与计数的对账一致性、并发写入正确性。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PercentileMetricsIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PercentileMetricsIntegrationTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObservationRegistry registry;

    @BeforeEach
    void reset() {
        registry.reset();
    }

    @Test
    @DisplayName("空指标：分位数与成功率全为 0，不出现 NaN")
    void emptyMetricsAreZeroNotNaN() throws Exception {
        MvcResult result = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        log.info("[输入] 无任何请求直接读 /internal/metrics | [依据] 空数据 => "
                + "successRate=0、p50/p95/p99=0、total=0，实际={}", json);
        assertEquals(0, json.path("total").asLong());
        assertEquals(0.0d, json.path("successRate").asDouble());
        for (Outcome o : Outcome.values()) {
            JsonNode one = json.path("byOutcome").path(o.name());
            assertEquals(0L, one.path("p50Nanos").asLong(), o + " p50 必须为 0");
            assertEquals(0L, one.path("p95Nanos").asLong(), o + " p95 必须为 0");
            assertEquals(0L, one.path("p99Nanos").asLong(), o + " p99 必须为 0");
            assertEquals(0, one.path("latencySampleSize").asInt());
        }
        assertEquals(0L, json.path("overall").path("p99Nanos").asLong());
    }

    @Test
    @DisplayName("混合请求后：分位数单调、样本数与计数对得上、成功率正确")
    void percentilesConsistentWithCounts() throws Exception {
        // 3 成功 + 1 业务拒绝 + 1 超时
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/demo/sync").param("scenario", "success"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/demo/sync").param("scenario", "reject"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/demo/sync").param("scenario", "timeout")
                        .param("timeoutMillis", "10"))
                .andExpect(status().isGatewayTimeout());

        MvcResult result = mockMvc.perform(get("/internal/metrics"))
                .andExpect(status().isOk()).andReturn();
        JsonNode json = MAPPER.readTree(result.getResponse().getContentAsByteArray());

        long total = json.path("total").asLong();
        double successRate = json.path("successRate").asDouble();
        JsonNode success = json.path("byOutcome").path("SUCCESS");
        JsonNode overall = json.path("overall");
        log.info("[输入] 3x success + 1x reject + 1x timeout | [依据] total=5、successRate=0.6、"
                + "各归类样本数=计数、overall 样本数=total；实际 total={} successRate={} "
                + "SUCCESS.samples={} overall.samples={}", total, successRate,
                success.path("latencySampleSize").asInt(), overall.path("latencySampleSize").asInt());

        assertEquals(5, total);
        assertEquals(0.6d, successRate, 1e-9);
        // 样本量未超容量时，分位数样本数必须与计数完全一致（对得上账）
        for (Outcome o : Outcome.values()) {
            JsonNode one = json.path("byOutcome").path(o.name());
            assertEquals(one.path("count").asLong(), one.path("latencySampleSize").asLong(),
                    o + " 样本数必须等于计数");
        }
        assertEquals(total, overall.path("latencySampleSize").asLong());
        // 有数据的归类分位数必须为正且单调不降
        assertTrue(success.path("p50Nanos").asLong() > 0);
        assertTrue(success.path("p50Nanos").asLong() <= success.path("p95Nanos").asLong());
        assertTrue(success.path("p95Nanos").asLong() <= success.path("p99Nanos").asLong());
        // 超时的那次 ~120ms，overall p99 必须不小于 success 的 p50（长尾可见）
        assertTrue(overall.path("p99Nanos").asLong() >= success.path("p50Nanos").asLong());
    }

    @Test
    @DisplayName("MetricsStore 并发写入：计数精确、分位数落在已知值域、与计数对得上")
    void concurrentStoreWrites() throws Exception {
        MetricsStore store = new MetricsStore(512);
        int threads = 8;
        int perThread = 200; // 共 1600 条，超过容量 512，验证淘汰后样本数=容量
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.record(Outcome.SUCCESS, 2_000_000L + i * 1000L); // 2ms~2.2ms
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        long expected = (long) threads * perThread;
        MetricsStore.LatencyStats stats = store.latencyStats(Outcome.SUCCESS);
        MetricsStore.LatencyStats overall = store.overallLatencyStats();
        log.info("[输入] {} 线程 x {} 次 record(2ms 级)，容量 512 | [依据] count={} 精确、"
                + "样本数=容量 512、p50/p95/p99 落在 [2000000, 2199000] 且单调；实际 p50={} p95={} p99={}",
                threads, perThread, expected, stats.p50Nanos(), stats.p95Nanos(), stats.p99Nanos());

        assertEquals(expected, store.count(Outcome.SUCCESS), "并发计数一次都不能丢");
        assertEquals(expected, store.totalCount());
        assertEquals(512, stats.sampleSize(), "写满后样本数恒等于容量");
        assertEquals(512, overall.sampleSize());
        assertTrue(stats.p50Nanos() >= 2_000_000L && stats.p99Nanos() <= 2_000_000L + 199_000L);
        assertTrue(stats.p50Nanos() <= stats.p95Nanos() && stats.p95Nanos() <= stats.p99Nanos());
        assertEquals(1.0d, store.successRate(), "全是 SUCCESS 时成功率必须为 1");
    }
}
