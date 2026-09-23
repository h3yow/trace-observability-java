package com.github.h3yow.traceobservabilityjava.tests;

import com.github.h3yow.traceobservabilityjava.observation.MetricsStore;
import com.github.h3yow.traceobservabilityjava.observation.Observation;
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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 高并发混合场景：多线程同时发起 success/reject/timeout/bug 请求，
 * 校验三件事：
 * 1) 每个请求拿到全局唯一 traceId，互不串扰；
 * 2) 指标守恒：total == 各分类计数之和 == 请求总数（每请求恰好计数一次）；
 * 3) 每条 Observation 都恰好落定一次、归类与 HTTP 状态一致。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentLoadIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentLoadIntegrationTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObservationRegistry registry;

    @BeforeEach
    void resetMetrics() {
        registry.reset();
    }

    @Test
    @DisplayName("200 并发混合请求：traceId 唯一、指标守恒、归类与状态一致")
    void concurrentMixedLoad() throws Throwable {
        int clients = 32;
        int perClient = 6; // 总计 192 个请求，其中约 1/4 timeout（每次 sleep 120ms）
        int total = clients * perClient;

        ExecutorService pool = Executors.newFixedThreadPool(clients);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        AtomicInteger failures = new AtomicInteger();
        ConcurrentHashMap<String, Integer> traceIds = new ConcurrentHashMap<>();

        for (int c = 0; c < clients; c++) {
            final int clientId = c;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perClient; i++) {
                        int kind = (clientId + i) % 4;
                        try {
                            String scenario = switch (kind) {
                                case 0 -> "success";
                                case 1 -> "reject";
                                case 2 -> "timeout";
                                default -> "bug";
                            };
                            MvcResult result = mockMvc.perform(get("/api/demo/sync")
                                            .param("scenario", scenario)
                                            .param("timeoutMillis", "10"))
                                    .andReturn();
                            String traceId = result.getResponse().getHeader("X-Trace-Id");
                            traceIds.merge(traceId, 1, Integer::sum);
                        } catch (Exception e) {
                            failures.incrementAndGet();
                            log.error("request_error client={} i={}", clientId, i, e);
                        } finally {
                            done.countDown();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        long t0 = System.currentTimeMillis();
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "请求应在超时前全部完成");
        long elapsed = System.currentTimeMillis() - t0;
        pool.shutdown();

        log.info("[输入] 并发={} 总量={} 混合 success/reject/timeout/bug | 耗时={}ms，请求失败={}",
                clients, total, elapsed, failures.get());

        assertEquals(0, failures.get(), "不应有请求级失败");
        assertEquals(total, traceIds.size(),
                "每个请求必须有全局唯一 traceId（出现重复即上下文串扰/复用泄漏）");

        MetricsStore m = registry.metrics();
        long sumByOutcome = m.count(Outcome.SUCCESS) + m.count(Outcome.BUSINESS_REJECTED)
                + m.count(Outcome.DEPENDENCY_TIMEOUT) + m.count(Outcome.UNEXPECTED_ERROR);
        log.info("[判定依据] total={} SUCCESS={} REJECTED={} TIMEOUT={} UNEXPECTED={} 分类求和={}",
                m.totalCount(), m.count(Outcome.SUCCESS), m.count(Outcome.BUSINESS_REJECTED),
                m.count(Outcome.DEPENDENCY_TIMEOUT), m.count(Outcome.UNEXPECTED_ERROR),
                sumByOutcome);

        assertEquals(total, m.totalCount(), "指标总数必须等于请求总数（不重不漏）");
        assertEquals(total, sumByOutcome, "各分类计数之和必须等于总数");
        assertEquals(total / 4, m.count(Outcome.SUCCESS));
        assertEquals(total / 4, m.count(Outcome.BUSINESS_REJECTED));
        assertEquals(total / 4, m.count(Outcome.DEPENDENCY_TIMEOUT));
        assertEquals(total / 4, m.count(Outcome.UNEXPECTED_ERROR));

        // 每条 Observation：恰好落定一次，且归类与状态码自洽
        long settled = 0;
        for (Observation o : registry.all()) {
            assertTrue(o.isSettled(), "存在未归类请求 traceId=" + o.traceId());
            settled++;
            Outcome byPolicy = switch (o.finalHttpStatus()) {
                case 200 -> Outcome.SUCCESS;
                case 422 -> Outcome.BUSINESS_REJECTED;
                case 504 -> Outcome.DEPENDENCY_TIMEOUT;
                default -> Outcome.UNEXPECTED_ERROR;
            };
            assertEquals(byPolicy, o.finalOutcome(),
                    "归类与状态码不一致 traceId=" + o.traceId());
        }
        assertEquals(total, settled, "每个请求都要有一条已落定的 Observation");
    }
}
