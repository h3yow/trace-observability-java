# trace-observability-java

基于 Spring Boot 的**完全本地、零外部采集后端**的请求可观测链路：把
**分布式追踪（Trace）、结构化日志（Structured Logging）、指标（Metrics）**
串成对"同一个请求"可解释、可验证的结论。

所有采集结果都保存在**进程内内存**，通过 HTTP 端点或单元测试即可直接读取与断言，
不依赖 Jaeger/Zipkin/Prometheus 等任何外部系统。

---

## 1. 能力总览

| 维度 | 实现 | 本地获取方式 |
| --- | --- | --- |
| 追踪 | 每个请求一个 `traceId` + `spanId` + `requestId`，ThreadLocal + 线程池快照传播；兼容 W3C `traceparent` 标准头与自定义头 | 响应头 `X-Trace-Id` / `traceparent`、JSON 日志、`/internal/*` |
| 结构化日志 | 自研零依赖 JSON Logback Encoder，每行平铺 traceId/spanId/requestId | 控制台、`logs/observability.json.log` |
| 指标 | 按归类计数 + 累计/平均耗时 + **p50/p95/p99 分位数（有界蓄水池采样）+ 成功率**，线程安全 | `GET /internal/metrics` |
| 链路明细 | 每请求的最终归类、HTTP 状态、每次"尝试"记录 | `GET /internal/observations`、`/internal/observation?traceId=` |

---

## 2. 追踪传播规则

### 2.1 traceId 的来源（沿用上游 vs 新生成）

系统同时理解两种上游追踪头，取舍规则**固定且唯一**：

| 优先级 | 来源 | 条件 | 结果 |
| --- | --- | --- | --- |
| 1 | W3C `traceparent` 标准头 | 严格校验通过（见下） | 采用其 trace-id，父 span 关联，`traceSource=w3c` |
| 2 | 自定义头 `X-Trace-Id`（可配置） | 匹配可配置正则（默认 `^[0-9a-fA-F]{16,32}$`） | 原样沿用，`traceSource=upstream`（**原有行为不变**） |
| 3 | —— | 两者都缺失/非法 | `SecureRandom` 生成 32 位小写 hex，`traceSource=generated` |

一条请求只会落进其中一行，日志、指标、响应头共用同一个最终标识，
不存在"一条请求两套标识"。

`traceparent`（`version-traceid-spanid-flags`）按 W3C 规范严格校验，以下一律判**无效**
（回退到下一优先级，并打印 `traceparent_invalid` 警告日志）：

- 缺字段 / 多字段 / 空段；
- version 非 2 位小写 hex 或为保留值 `ff`；
- trace-id 非 32 位小写 hex，或**全零**（规范明令不可信）；
- span-id 非 16 位小写 hex，或全零；
- flags 非 2 位小写 hex；出现大写字母。

**响应回写**：无论标识来自哪里，响应同时回写
`X-Trace-Id: <最终traceId>` 与 `traceparent: 00-<最终traceId>-<spanId>-01`，
上游用任一种格式都能关联。

### 2.2 同步与异步/后台之间如何保持一致

- 请求入口 `TracingFilter`（最高优先级）建立 `TraceContext`，写入：
  - `TraceContextHolder`（ThreadLocal）
  - `ObservationHolder`（ThreadLocal，持有该请求的观测聚合对象）
  - SLF4J `MDC`（traceId/spanId/requestId，供日志关联）
- 业务通过专用线程池 Bean `traceAwareExecutor`
  （`ContextPropagatingExecutorService`）提交任务：
  - **在提交方线程**用 `ContextSnapshot.capture()` 对 TraceContext、Observation、MDC
    做不可变快照；
  - 在工作线程 **run 前恢复**快照，**finally 中还原工作线程原状态**。
- 因为 `AbstractExecutorService` 的 `submit/invokeAll/invokeAny` 最终都走 `execute`，
  所以 `CompletableFuture.supplyAsync(..., executor)` 等所有提交方式都会自动传播。
- 子任务可用 `traceContext.withNewChildSpan(...)` 生成子 span：
  **traceId/requestId 不变，spanId 新建并以父 spanId 关联**。

### 2.2.1 真异步（非阻塞）请求的结论落定

`GET /api/demo/async-nonblocking` 返回 `CompletableFuture`：Servlet 容器在异步启动后
**立即释放入口线程**，结果由 `traceAwareExecutor` 工作线程算完后异步派发写回响应。

- 入口过滤器在 `chain.doFilter` 返回时检测 `isAsyncStarted()`：
  异步中则**不提前下结论**，而是注册 `AsyncListener`，
  在 `onComplete / onError / onTimeout` 时用**最终** HTTP 状态与最终异常
  （异常处理器在异步派发阶段挂到 request 属性上）走同一个 `settle` 落定；
- 落定仍是 `Observation` 的 CAS 一次性语义：监听器与兜底检查并发触发、
  容器重复回调，都只会计数一次，先到结论不被后到覆盖；
- 执行中（`worker_context_ok`）、完成回调（`async_callback`）、
  出错（`dependency_timeout` 等）各段日志都带同一 traceId；
  异步派发线程的异常处理器通过 request 属性上的 Observation 取回 traceId，
  不会因 ThreadLocal 已清理而丢标识。

### 2.3 线程复用防泄漏（强约束）

工作线程执行任务时遵循"**先清空 → 写快照 → 执行 → finally 清空并还原**"：

- 任务结束无条件 `TraceContextHolder.clear()` / `ObservationHolder.clear()` / `MDC.clear()`；
- 即使任务抛异常也会清理（finally）；
- 因此池内同一线程先后执行不同请求时，第二个任务**绝不可能**读到上一个请求的标识。
- 入口 Tomcat 线程同样在 `TracingFilter` 的 finally 中彻底清理。

> 校验：`ThreadReuseLeakTest` 用"单线程池串行两个请求 + 空上下文第三个任务"
> 强制复用同一工作线程，断言无残留；`ConcurrentLoadIntegrationTest` 用 192 个并发请求
> 断言所有 traceId 全局唯一（出现重复即代表串扰）。

---

## 3. 结果归类口径（每请求唯一）

四类结果**互斥**，每个请求**有且仅有一个**最终归类：

| Outcome | 含义 | 判定依据 | HTTP 状态 |
| --- | --- | --- | --- |
| `SUCCESS` | 正常完成 | 无异常且 2xx | 200 |
| `BUSINESS_REJECTED` | 业务拒绝 | `BusinessRejectedException` 或 4xx | 422 |
| `DEPENDENCY_TIMEOUT` | 依赖超时 | `DependencyTimeoutException` / `java.util.concurrent.TimeoutException`（**沿异常 cause 链查找**，故 `CompletionException` 等包装不影响识别） | 504 |
| `UNEXPECTED_ERROR` | 未预期异常 | 其余异常 / 5xx | 500 |

判定顺序（在请求出口统一执行一次）：**有效异常优先（区分度更高），否则用 HTTP 状态码**。

### 3.1 为什么重试/嵌套/分支不会重复计数或覆盖

分两层防护：

1. **尝试层幂等去重**：业务对一次"依赖尝试"用 `attemptId` 上报
   （`ObservationContext.recordAttempt`）。重试使用**稳定编号** `attempt-1/2/3`，
   同一 attemptId 重复上报（例如异常处理重入、多个回调）会被
   `ConcurrentHashMap.putIfAbsent` 忽略并打印 `attempt_duplicate_ignored`；
   不同 attemptId（真正的多次尝试）各自保留。
2. **结论层 CAS 一次性落定**：请求最终归类通过
   `AtomicReference.compareAndSet(null, outcome)` 落定。
   - 只有**第一次** settle 返回 `true` 并向指标计数一次；
   - 之后任何线程、任何分支、任何迟到的结论（例如请求已返回后才完成的后台任务）
     再 settle 都返回 `false`，**先到结论不被后到覆盖**。
   - 指标计数 `MetricsStore.record` 只在首次落定的回调中执行，
     所以"一个请求恰好计数一次"，且重试 N 次也只贡献 1 到总数。

> 异步后台任务（`/api/demo/background`）可能在请求结束后才完成：
> 它的尝试会聚合进同一条 Observation（可解释），但**不会**改变已落定的最终归类，
> 也不会再次计数。

### 3.2 指标守恒（可作为自检不变量）

```
total == count(SUCCESS)+count(BUSINESS_REJECTED)
       + count(DEPENDENCY_TIMEOUT)+count(UNEXPECTED_ERROR) == 请求总数
```

`ConcurrentLoadIntegrationTest` 在 192 个并发混合请求后严格断言该等式，
并逐请求校验"已落定且归类与状态码一致"。

### 3.3 分位数与成功率口径

`/internal/metrics` 在计数之外，为**每个归类**以及**整体（overall）**提供
p50/p95/p99 分位数与成功率：

- **分位数口径**：最近秩（nearest-rank）——`rank = ceil(p/100 × n)`，
  取升序排序后第 rank 个样本（1 起）。空数据集返回 0，单样本时所有分位数等于该样本，
  **任何情况下不返回 NaN**。
- **采样与淘汰**：每个样本集是有界**蓄水池（reservoir，Algorithm R）**，
  容量由 `observability.latency-sample-capacity` 控制（默认 2048）：
  - 样本数 ≤ 容量：**全量保留**，分位数为精确值；
  - 样本数 > 容量：以 `capacity/seen` 概率等概率替换，
    任一历史样本被保留的概率恒为 `capacity/seen`（无偏），**内存恒为 O(容量)**；
  - 响应中的 `sampleSize`（当前保留数）、`samplesSeen`（历史累计）、
    `sampleCapacity`（容量上限）可用于判断是否已进入采样态。
- **成功率**：`successRate = count(SUCCESS) / total`，读取时由精确计数推导，
  无请求时返回 0.0；恒在 [0,1]。
- **一致性**：每个请求落定时恰好写入一个样本（某归类 + 整体各一），
  因此 `samplesSeen` 与计数严格对账；分位数样本与计数不会脱节。

---

## 4. 可配置项（`src/main/resources/application.properties`）

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `observability.trace-header` | `X-Trace-Id` | 读取/回写上游 traceId 的自定义请求头名（W3C `traceparent` 始终同时被识别，优先级更高） |
| `observability.trace-id-pattern` | `^[0-9a-fA-F]{16,32}$` | 合法 traceId 正则；不匹配则新生成 |
| `observability.trace-id-length` | `32` | 生成 traceId 的十六进制长度（自动夹在 16~32 以保证合法） |
| `observability.async-pool-size` | `8` | 传播追踪上下文的工作线程池大小 |
| `observability.max-observations` | `1000` | 进程内保留的链路明细条数上限（FIFO 淘汰；**计数指标不受淘汰影响**） |
| `observability.latency-sample-capacity` | `2048` | 每个耗时样本集（各归类 + 整体）的蓄水池容量上限；超出后等概率无偏替换，内存恒定 |

---

## 5. HTTP 接口

业务演示端点（`scenario` ∈ `success`/`reject`/`timeout`/`bug`）：

| 方法路径 | 说明 |
| --- | --- |
| `GET /api/demo/sync?scenario=&timeoutMillis=` | 纯同步链路 |
| `GET /api/demo/async?scenario=&timeoutMillis=` | 线程池异步后等待（验证跨线程传播） |
| `GET /api/demo/async-nonblocking?scenario=&timeoutMillis=` | **真异步非阻塞**：入口线程立即释放，结果后台算完后异步写回，全程同一 traceId |
| `GET /api/demo/retry?maxAttempts=3&failFirstN=1&timeoutMillis=10` | 前 N 次超时后成功/失败，验证重试去重与唯一归类 |
| `GET /api/demo/background?scenario=` | 发射后台任务立即返回，验证迟到尝试不覆盖结论 |

本地采集读取（进程内，无后端）：

| 方法路径 | 说明 |
| --- | --- |
| `GET /internal/metrics` | 按归类的 count / totalNanos / avgNanos、**p50/p95/p99 分位数**、整体分位数与 **successRate** |
| `GET /internal/observations` | 所有请求的最终归类、状态、尝试明细 |
| `GET /internal/observation?traceId=xxx` | 单条链路的可解释视图 |

---

## 6. 本地验证方法

### 6.1 运行测试（推荐，覆盖并发与异常链路）

```bash
mvn test
```

共 52 个用例，全部在本地 JVM 内完成，无需任何外部服务：

- `TracePropagatorTest`：上游标识缺失/非法（含注入串）→ 生成；合法 → 沿用；子 span 关联。
- `W3cTraceparentTest`：traceparent 合法解析；缺字段/全零/大写/保留版本 ff 一律拒绝；回写格式可再解析。
- `W3cTraceContextIntegrationTest`：双头共存时 traceparent 优先、非法 traceparent 回退自定义头、两者皆非法重新生成、响应双头回写同一最终标识。
- `OutcomePolicyTest`：四类归类、JDK 超时、**深层包装异常**、按状态码归类。
- `ObservationDedupTest`：attemptId 去重、CAS 唯一落定、**64 线程并发 settle** 仅 1 次成功。
- `ThreadReuseLeakTest`：同一工作线程串行多请求不串扰、任务后无残留、200 任务/4 线程隔离。
- `AsyncNonBlockingIntegrationTest`：真异步成功/异常下入口线程提前释放、跨线程同一 traceId、归类只落定一次、并发异步指标守恒。
- `LatencyPercentileTest`：空数据不 NaN、单样本、最近秩精确值、**16 线程并发写 16000 样本与离线排序完全一致**、超容量内存有界、成功率对账。
- `MetricsPercentileEndpointTest`：`/internal/metrics` 空数据 0 值、混合请求后 successRate 与分位数样本量和计数对账。
- `HttpObservabilityIntegrationTest`：真实 MockMvc 全链路（含超时/重试/异步/后台）。
- `ConcurrentLoadIntegrationTest`：192 个并发混合请求，traceId 唯一 + 指标守恒。
- `InternalMetricsEndpointTest`：`/internal/metrics` 数字守恒、单链路明细可解释。

测试日志为 JSON，且每个用例都打印 **`[输入] ... | [依据] ...`**，失败原因可区分。

### 6.2 启动后手工验证

```bash
mvn spring-boot:run
```

```bash
# 缺失 traceId（观察响应头被生成）
curl -i http://localhost:8080/api/demo/sync

# 合法上游标识被沿用（自定义头）
curl -i -H "X-Trace-Id: aabbccddeeff00112233445566778899" \
  http://localhost:8080/api/demo/sync

# W3C 标准头被采用（注意响应同时回写 X-Trace-Id 与 traceparent）
curl -i -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  http://localhost:8080/api/demo/sync

# 双头共存：traceparent 优先，自定义头的值被舍弃
curl -i -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  -H "X-Trace-Id: aabbccddeeff00112233445566778899" \
  http://localhost:8080/api/demo/sync

# 全零 trace-id 不可信：回退/重新生成（日志出现 traceparent_invalid）
curl -i -H "traceparent: 00-00000000000000000000000000000000-00f067aa0ba902b7-01" \
  http://localhost:8080/api/demo/sync

# 非法标识被替换
curl -i -H "X-Trace-Id: evil-injected-value" http://localhost:8080/api/demo/sync

# 四类结果
curl -i "http://localhost:8080/api/demo/sync?scenario=reject"                 # 422
curl -i "http://localhost:8080/api/demo/sync?scenario=timeout&timeoutMillis=10" # 504
curl -i "http://localhost:8080/api/demo/sync?scenario=bug"                    # 500

# 异步 / 重试（先超时后成功，最终仍 SUCCESS 且只计数一次）
curl "http://localhost:8080/api/demo/async?scenario=success"
curl "http://localhost:8080/api/demo/retry?maxAttempts=3&failFirstN=1&timeoutMillis=10"

# 真异步非阻塞：入口线程立即释放，响应由工作线程算完后写回
curl -i "http://localhost:8080/api/demo/async-nonblocking?scenario=success"
curl -i "http://localhost:8080/api/demo/async-nonblocking?scenario=timeout&timeoutMillis=10"  # 504

# 读取进程内指标：分位数 p50/p95/p99、successRate、采样水位
curl http://localhost:8080/internal/metrics
curl "http://localhost:8080/internal/observation?traceId=<上一步的traceId>"
```

用 traceId 把同一请求的所有日志（含跨线程）串起来复盘：

```bash
grep '<traceId>' logs/observability.json.log
```

可看到入口线程的 `request_start/request_end` 与 `pool-*-thread-*` 工作线程的
`worker_context_ok`、`attempt_recorded` 携带完全相同的 traceId/requestId。

---

## 7. 关键类索引

| 类 | 职责 |
| --- | --- |
| `trace.TracingFilter` | 请求入口：双头取舍建上下文、写 MDC、出口唯一归类落定（同步直接落定 / 异步注册 AsyncListener 延迟落定）、finally 清理 |
| `trace.TracePropagator` | 上游标识校验、traceId/spanId 生成、根上下文 |
| `trace.W3cTraceparent` | W3C `traceparent` 解析/严格校验（全零拒绝）与响应回写格式化 |
| `trace.TraceContext` / `TraceContextHolder` | 不可变上下文 / ThreadLocal |
| `trace.ContextSnapshot` | 提交时快照 + 工作线程恢复/还原（防泄漏核心） |
| `trace.ContextPropagatingExecutor(Service)` | 自动传播上下文的 Executor/ExecutorService |
| `observation.Outcome(Policy)` | 四类归类与判定口径 |
| `observation.Observation` | 尝试去重（putIfAbsent）+ 结论 CAS 落定 |
| `observation.ObservationContext/Holder` | 请求级观测句柄及其 ThreadLocal |
| `observation.ObservationRegistry/MetricsStore` | 进程内注册表与线程安全指标（计数 + 分位数样本 + 成功率） |
| `observation.LatencySamples` | 有界蓄水池采样（Algorithm R）+ 最近秩分位数，空数据不 NaN |
| `logging.JsonLogEncoder` | 零依赖结构化 JSON 日志（平铺 MDC） |
| `web.GlobalExceptionHandler` | 三类异常 → 422/504/500（不计数，计数只在过滤器；异步派发线程从 request 属性取回 traceId） |
