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
| 追踪 | 每个请求一个 `traceId` + `spanId` + `requestId`，ThreadLocal + 线程池快照传播；同时兼容 W3C `traceparent` 与自定义头 | 响应头 `X-Trace-Id` + `traceparent`、JSON 日志、`/internal/*` |
| 结构化日志 | 自研零依赖 JSON Logback Encoder，每行平铺 traceId/spanId/requestId | 控制台、`logs/observability.json.log` |
| 指标 | 按归类计数 + 累计/平均耗时 + p50/p95/p99 分位数 + 成功率，线程安全（AtomicLong/CAS + 有界环形样本池） | `GET /internal/metrics` |
| 链路明细 | 每请求的最终归类、HTTP 状态、每次"尝试"记录 | `GET /internal/observations`、`/internal/observation?traceId=` |

---

## 2. 追踪传播规则

### 2.1 traceId 的来源（W3C / 自定义头 / 新生成）

系统同时接受两种上游追踪头，一次请求**只产出一套标识**，取舍规则明确且与到达顺序无关：

1. **W3C `traceparent`**（`version-traceId-spanId-flags`，如
   `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`）合法 → **优先采用**：
   沿用其中的 traceId，spanId 作为本服务根 span 的父 span（来源记为 `w3c`）。
   合法判定：恰好 4 段、version 为 2 位 hex 且不为 `ff`、traceId 为 32 位 hex、
   spanId 为 16 位 hex、flags 为 2 位 hex，且 **traceId/spanId 都不得全零**
   （全零属规范定义的不可信值）。缺字段、非 hex、长度错、`ff` 版本、全零 → 一律按无效处理。

   **大小写兼容范围（严格对齐 W3C Trace Context ABNF）**：四段中的十六进制字符**只接受小写**
   `0-9a-f`。任意一段、任意一位出现大写字母（整段大写或大小写混合，如
   `00-4BF92F...-00f067aa0ba902b7-01`、`...-0F`）都与缺字段/全零一样属格式不合法：
   系统**不会**把它转成小写后沿用，其中的 traceId 视为不可信，既不进入日志、指标，
   也不会出现在响应头里。系统不做大小写归一化，因此只有完全合法的小写标准头才允许优先于自定义头。
2. 标准头不合法时，若**自定义头** `X-Trace-Id`（可配置 `observability.trace-header`）合法
   （匹配可配置正则，默认 `^[0-9a-fA-F]{16,32}$`，自定义头仍允许大写）
   → 回退沿用自定义头（来源 `upstream`），既有行为不变；标准头与自定义头都合法时仍是标准头优先。
3. 两者都缺失/非法 → 服务端用 `SecureRandom` 生成新的 32 位小写十六进制 traceId
   （来源 `generated`），结果与入站脏值必然不同。

**响应回写**：无论来源，响应同时回写 `X-Trace-Id: <最终 traceId>` 与
`traceparent: 00-<最终 traceId>-<本服务 spanId>-01` 两个头，值是**最终实际采用**的同一套标识，
上游用任一头都能关联；日志字段 `traceSource`（`w3c`/`upstream`/`generated`）标明取舍结果，
日志、指标、响应头三方永远对得上。

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

### 2.3 真·非阻塞异步（入口线程不等待结果）

`GET /api/demo/nonblocking` 返回 `CompletableFuture`：Servlet 容器发现返回值是异步句柄后，
**入口请求线程立即释放**回连接池去服务别的请求，业务在 `traceAwareExecutor` 工作线程上
算完后由容器回调把响应写完。整条链路的口径：

- **同一条链路**：工作线程经快照传播拿到同一 traceId/requestId/Observation；
  完成回调与异常处理都发生在同一请求对象上，响应头、响应体、日志、指标里的
  traceId 完全一致。
- **归类只在异步真正完成时落定一次**：`TracingFilter` 发现 `isAsyncStarted()` 后
  不在入口线程落定，而是注册 `AsyncListener`，在 `onComplete` 回调里用与同步路径
  完全相同的口径（有效异常优先，其次状态码）做唯一一次 CAS 落定；
  `onError/onTimeout` 不单独落定，避免迟到结论覆盖先到结论。
- **异步异常可识别**：异步 dispatch 线程上没有 ThreadLocal，`GlobalExceptionHandler`
  回退到请求属性里的 Observation 取 traceId，异常日志与错误响应体仍携带同一标识。

### 2.4 线程复用防泄漏（强约束）

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

### 3.3 延迟分位数与成功率（口径与淘汰规则）

`/internal/metrics` 在计数之外，为**每个归类**以及 **overall（全部请求）**分别给出
`p50/p95/p99`（同时提供 Nanos 与 Millis 两种单位）和整体 `successRate`
（= SUCCESS 数 / 总数，空数据为 0，绝不出现 NaN）。

- **分位数口径**：最近邻秩（nearest-rank）——把窗口内样本升序排序后取第
  `ceil(q × n)` 个；空窗口返回 0，单样本时任何分位数都等于该样本。
- **采样与淘汰**：每个归类一个容量固定（默认 2048，可配
  `observability.latency-reservoir-size`）的**环形样本池**，内存占用恒定；
  写满后新样本覆盖最老样本，即分位数始终反映"最近 N 次"请求的长尾。
  **计数、累计耗时、成功率是全量精确值，不受淘汰影响**；
  样本数未超容量时 `latencySampleSize == count`，可直接对账。
- **并发正确性**：写入走 `AtomicLong` 槽位分配 + `AtomicLongArray`，
  高并发下写入次数精确不丢（`LatencyReservoirTest`、
  `PercentileMetricsIntegrationTest` 用 16 线程 × 500 次、8 线程 × 200 次验证）。

---

## 4. 可配置项（`src/main/resources/application.properties`）

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `observability.trace-header` | `X-Trace-Id` | 读取/回写上游 traceId 的自定义请求头名（W3C `traceparent` 固定同时识别，无需配置） |
| `observability.trace-id-pattern` | `^[0-9a-fA-F]{16,32}$` | 合法 traceId 正则；不匹配则新生成 |
| `observability.trace-id-length` | `32` | 生成 traceId 的十六进制长度（自动夹在 16~32 以保证合法） |
| `observability.async-pool-size` | `8` | 传播追踪上下文的工作线程池大小 |
| `observability.max-observations` | `1000` | 进程内保留的链路明细条数上限（FIFO 淘汰；**计数指标不受淘汰影响**） |
| `observability.latency-reservoir-size` | `2048` | 每个归类（及 overall）延迟分位数样本池容量；写满环形淘汰最老样本 |

---

## 5. HTTP 接口

业务演示端点（`scenario` ∈ `success`/`reject`/`timeout`/`bug`）：

| 方法路径 | 说明 |
| --- | --- |
| `GET /api/demo/sync?scenario=&timeoutMillis=` | 纯同步链路 |
| `GET /api/demo/async?scenario=&timeoutMillis=` | 线程池异步后等待（验证跨线程传播） |
| `GET /api/demo/nonblocking?scenario=&timeoutMillis=` | 真·非阻塞：返回 CompletableFuture，入口线程立即释放，后台算完后写响应 |
| `GET /api/demo/retry?maxAttempts=3&failFirstN=1&timeoutMillis=10` | 前 N 次超时后成功/失败，验证重试去重与唯一归类 |
| `GET /api/demo/background?scenario=` | 发射后台任务立即返回，验证迟到尝试不覆盖结论 |

本地采集读取（进程内，无后端）：

| 方法路径 | 说明 |
| --- | --- |
| `GET /internal/metrics` | 按归类的 count / totalNanos / avgNanos / **p50/p95/p99**（Nanos+Millis）/ latencySampleSize，外加 overall 视图与 **successRate** |
| `GET /internal/observations` | 所有请求的最终归类、状态、尝试明细 |
| `GET /internal/observation?traceId=xxx` | 单条链路的可解释视图 |

---

## 6. 本地验证方法

### 6.1 运行测试（推荐，覆盖并发与异常链路）

```bash
mvn test
```

共 65 个用例，全部在本地 JVM 内完成，无需任何外部服务：

- `TracePropagatorTest`：上游标识缺失/非法（含注入串）→ 生成；合法 → 沿用；子 span 关联。
- `W3CTraceparentTest`：traceparent 解析（合法/缺字段/非 hex/**全零**/`ff` 版本/
  **各字段大写与大小写混合一律拒绝**/合法小写回归）、
  两种来源共存时的取舍规则（小写合法 W3C 优先 → 标准头大写非法时回退自定义头 → 都不可用则生成）。
- `W3CPropagationIntegrationTest`：端到端验证双头共存优先级、非法（含大写）回退、
  响应双头回写同一套标识且脏 trace-id 不入日志/指标、仅自定义头时旧行为不变。
- `OutcomePolicyTest`：四类归类、JDK 超时、**深层包装异常**、按状态码归类。
- `ObservationDedupTest`：attemptId 去重、CAS 唯一落定、**64 线程并发 settle** 仅 1 次成功。
- `ThreadReuseLeakTest`：同一工作线程串行多请求不串扰、任务后无残留、200 任务/4 线程隔离。
- `HttpObservabilityIntegrationTest`：真实 MockMvc 全链路（含超时/重试/异步/后台）。
- `NonBlockingAsyncIntegrationTest`：真·非阻塞成功/超时/未预期异常下的上下文一致性、
  入口线程释放（asyncStarted）、迟到结论不覆盖、异步链路沿用 W3C 标识。
- `LatencyReservoirTest`：空池/单样本不 NaN、已知分布分位数精确、环形淘汰、
  16 线程并发写入样本数与计数一致。
- `PercentileMetricsIntegrationTest`：空指标不 NaN、分位数与计数对账、
  成功率、8 线程并发 record 的正确性。
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

# 合法上游标识被沿用
curl -i -H "X-Trace-Id: aabbccddeeff00112233445566778899" \
  http://localhost:8080/api/demo/sync

# 非法标识被替换
curl -i -H "X-Trace-Id: evil-injected-value" http://localhost:8080/api/demo/sync

# W3C traceparent：合法则沿用其 traceId，响应同时回写 X-Trace-Id 与 traceparent
curl -i -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  http://localhost:8080/api/demo/sync

# 两种头同时出现：W3C 优先（响应两个头都是 traceparent 里的 traceId）
curl -i -H "X-Trace-Id: aabbccddeeff00112233445566778899" \
  -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01" \
  http://localhost:8080/api/demo/sync

# 全零 traceparent 不可信：回退到自定义头（或重新生成）
curl -i -H "traceparent: 00-00000000000000000000000000000000-00f067aa0ba902b7-01" \
  -H "X-Trace-Id: aabbccddeeff00112233445566778899" \
  http://localhost:8080/api/demo/sync

# 大写/混合大小写 traceparent 按 W3C 属非法：不沿用其 trace-id，回退到合法自定义头
# （响应两头都应是 aabbcc...，且回写的 traceparent 为全新小写值，不含入站大写 id）
curl -i -H "traceparent: 00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01" \
  -H "X-Trace-Id: aabbccddeeff00112233445566778899" \
  http://localhost:8080/api/demo/sync

# 仅一段一位大写（flags=0F）且无自定义头：标准头非法 => 重新生成
curl -i -H "traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0F" \
  http://localhost:8080/api/demo/sync

# 四类结果
curl -i "http://localhost:8080/api/demo/sync?scenario=reject"                 # 422
curl -i "http://localhost:8080/api/demo/sync?scenario=timeout&timeoutMillis=10" # 504
curl -i "http://localhost:8080/api/demo/sync?scenario=bug"                    # 500

# 异步 / 真·非阻塞 / 重试（先超时后成功，最终仍 SUCCESS 且只计数一次）
curl "http://localhost:8080/api/demo/async?scenario=success"
curl "http://localhost:8080/api/demo/nonblocking?scenario=success"
curl "http://localhost:8080/api/demo/retry?maxAttempts=3&failFirstN=1&timeoutMillis=10"

# 读取进程内指标（含 p50/p95/p99 与 successRate）与单链路
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
| `trace.TracingFilter` | 请求入口：双来源取舍建上下文、写 MDC、同步/异步（AsyncListener）唯一归类落定、finally 清理 |
| `trace.TracePropagator` | 双来源取舍（W3C > 自定义头 > 生成）、标识校验与生成、根上下文 |
| `trace.Traceparent` | W3C traceparent 解析与校验（缺字段/非 hex/全零/ff 版本/**大写或混合大小写 hex**均无效，仅小写合法） |
| `trace.TraceContext` / `TraceContextHolder` | 不可变上下文 / ThreadLocal |
| `trace.ContextSnapshot` | 提交时快照 + 工作线程恢复/还原（防泄漏核心） |
| `trace.ContextPropagatingExecutor(Service)` | 自动传播上下文的 Executor/ExecutorService |
| `observation.Outcome(Policy)` | 四类归类与判定口径 |
| `observation.Observation` | 尝试去重（putIfAbsent）+ 结论 CAS 落定 |
| `observation.ObservationContext/Holder` | 请求级观测句柄及其 ThreadLocal |
| `observation.ObservationRegistry/MetricsStore` | 进程内注册表与线程安全指标（计数 + 分位数 + 成功率） |
| `observation.LatencyReservoir` | 有界环形延迟样本池，p50/p95/p99 最近邻秩分位数 |
| `logging.JsonLogEncoder` | 零依赖结构化 JSON 日志（平铺 MDC） |
| `web.GlobalExceptionHandler` | 三类异常 → 422/504/500（不计数；异步 dispatch 线程经请求属性兜底 traceId） |
