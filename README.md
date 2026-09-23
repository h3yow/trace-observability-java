# trace-observability-java

一个基于 Spring Boot 3 / Java 21 的**完全本地、零外部后端**请求可观测链路示例：把
**追踪（trace）**、**结构化日志（logging）** 与 **指标（metrics）** 通过同一个
`traceId` 串成对一条请求可解释、可验证的结论。

采集结果全部保存在**当前 JVM 进程内存**中，通过 HTTP 端点或测试代码直接读取，
不依赖 Zipkin / Jaeger / Prometheus / ELK 等任何外部采集系统。

---

## 1. 能力总览

| 关注点 | 实现方式 | 本地获取入口 |
| --- | --- | --- |
| 追踪标识 | 每个请求一条 `traceId`，每次进入/跨线程一个 `spanId`，ThreadLocal 栈 + MDC | 响应头 `X-Trace-Id`、日志字段、`/observability/traces/{id}` |
| 上下文传播 | 提交线程快照 `TraceContext + MDC`，工作线程附着、结束恢复 | `/demo/async`、`/demo/background` 实测可见 |
| 结果归类 | 四档互斥类别，请求边界裁决一次 | `/observability/traces/{id}` 的 `outcome` |
| 唯一结论 | 每 traceId 一个 `AtomicReference`，CAS 提交，先到不被覆盖 | 指标 `/observability/metrics` |
| 结构化日志 | Logback 内存环形缓冲，日志自动带 `traceId/spanId` | `/observability/logs/{id}` |
| 指标 | LongAdder 计数 + 环形采样延迟（avg/min/max/p50/p95/p99） | `/observability/metrics` |

---

## 2. 追踪传播规则

### 2.1 入站标识

入口过滤器 `TraceFilter`（最高优先级，覆盖 REQUEST / ASYNC / ERROR 三种分发）按以下顺序处理：

1. 读取 `X-Trace-Id`（32 位小写十六进制）；
2. 缺失时读取 W3C [`traceparent`](https://www.w3.org/TR/trace-context/) 的第 2 段（trace-id）；
3. 两者都缺失**或非法**时，用 `SecureRandom` 生成新的 16 字节标识。

合法性规则（`TraceIds`）：长度必须为 32、仅含小写十六进制、**全 0 标识判为非法**
（W3C 明确规定全 0 无效）；`traceparent` 版本号为 `ff` 也判非法。

- 沿用上游时，链路记录 `upstreamProvided=true`；
- 新生成时为 `false`，并在日志中显式打印“缺少合法追踪标识，已生成新 traceId”；
- 无论哪种情况，响应都会回写 `X-Trace-Id`，便于调用方对账。

### 2.2 请求内传播（嵌套 span）

`TraceContextHolder` 用 **ThreadLocal 栈** 保存上下文：

- `push/pop` 支持 `controller → service → 多次依赖调用` 的嵌套出入；
- 每次 `push` 同步把 `traceId/spanId` 写入 SLF4J `MDC`，因此**所有日志自动带链路标识**，
  无需业务代码手工传递；
- 推荐用 `TraceContextHolder.attach(ctx)` 返回的 `Scope` 做 try-with-resources，
  异常路径也保证清理。

整条链路 `traceId` 恒定；每次进入子操作（如下游调用、跨线程）生成新的 `spanId`，
并通过 `parentSpanId` 串起层级。

### 2.3 跨线程传播（线程池 / 异步 / 后台任务）

业务线程池 `taskExecutor`（`ContextPropagatingTaskExecutor`，同时是 Spring MVC
处理控制器 `CompletableFuture` 的默认异步执行器）通过一个 `TaskDecorator`：

1. **提交时刻**（调用方线程）快照 `TraceContext` 与 MDC 副本；
2. 工作线程执行前，先保存自身旧状态，再附着快照；
3. 任务结束（含异常）后**恢复为旧状态**，而不是简单清空——因此“线程池里再提交任务”
   这类嵌套场景也正确；
4. 提交时没有上下文的任务会显式清空工作线程，**绝不把上一个请求的残留带给下一个任务**。

复制的只是两个小不可变对象和一个小 Map，开销为纳秒级。

> Web 异步（控制器返回 `CompletableFuture`）的结案时机由 `AsyncTracingInterceptor`
> 精确识别：只有控制器真正返回异步返回值时（Spring 回调
> `afterConcurrentHandlingStarted`），过滤器才把结案推迟到 ASYNC 重分发，
> **不依赖语义不稳定的 `request.isAsyncStarted()`**，避免同步请求被误判。

---

## 3. 结果归类口径

四档互斥、对一条请求**唯一**的类别（`OutcomeCategory`）：

| 类别 | 含义 | 典型触发 | HTTP |
| --- | --- | --- | --- |
| `SUCCESS` | 正常完成 | 2xx 且无异常 | 200 |
| `BUSINESS_REJECTED` | 业务拒绝（请求可处理但规则不接受） | `BusinessRejectionException` / 4xx | 422 |
| `DEPENDENCY_TIMEOUT` | 依赖超时 | `DependencyTimeoutException`、`java.util.concurrent.TimeoutException`、`java.net.SocketTimeoutException`（遍历异常 cause 链） | 504 |
| `UNEXPECTED_ERROR` | 未预期异常 | 其他异常 / 5xx | 500 |

裁决在唯一出口 `OutcomeResolver.resolve(status, error)` 完成，全序优先级：

1. 异常链中出现任一时钟超时类型 → `DEPENDENCY_TIMEOUT`；
2. 否则出现业务异常 → `BUSINESS_REJECTED`；
3. 否则有异常 → `UNEXPECTED_ERROR`；
4. 无异常再看状态码：4xx → 业务拒绝，5xx → 未预期异常，其余 → 成功。

### 3.1 “过程”与“结论”分离（重试不污染计数）

- 每次依赖尝试（成功/超时、第几次）只记录为一条 **`SpanEvent`**，用于还原过程；
- 归类**只在请求边界裁决一次**。

因此 `/demo/retry` 前两次依赖超时（产生 2 条 `DEPENDENCY_TIMEOUT` 事件）后重试成功，
请求最终唯一结论是 `SUCCESS`，请求级超时计数为 **0**——可被重试挽回的中间失败
不会被错误地统计成故障。

### 3.2 唯一结论如何保证

`InMemoryObservationStore` 为每个 `traceId` 维护一个条目：

- `start` 用 `putIfAbsent` 幂等创建；
- `finalize` 对结论字段做 **CAS**：
  - 只有**第一次**成功，先到结论生效；
  - 重试分支、异步分支、迟到裁决的二次提交直接失败，**不能覆盖先到结论**；
  - 只有 CAS 获胜的那一次更新计数器，所以**每条请求恰好计数一次**；
- `SpanEvent` 可由任意线程并发追加，加锁保护，永不参与归类与计数。

高并发验证见 `InMemoryObservationStoreTest`（16 线程并发结案同一条 traceId，
仅 1 次生效）与 `ConcurrentTrafficIntegrationTest`（192 混合并发请求计数精确）。

### 3.3 线程复用不串扰

- 请求边界 `finally` 中无条件 `TraceContextHolder.clear()`，Tomcat 工作线程复用不残留；
- 线程池任务结束恢复/清空（见 2.3）；
- 每个请求的 traceId、耗时、结论分别存在各自条目，互不影响。

---

## 4. 可配置项（`application.properties`，前缀 `observability.`）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `observability.enabled` | `true` | 总开关（预留） |
| `observability.store-capacity` | `10000` | 内存保留的链路条数上限，超出淘汰最旧 |
| `observability.log-buffer-capacity` | `2000` | 内存结构化日志环形缓冲条数 |
| `observability.async-pool-size` | `4` | 传播上下文的业务线程池大小 |
| `observability.dependency-timeout-millis` | `200` | 演示接口默认依赖超时预算 |

另：`logging.pattern.level` 已注入 `[traceId=%X{traceId:-}, spanId=%X{spanId:-}]`，
控制台日志与内存结构化日志同源。

---

## 5. HTTP 接口

### 5.1 演示业务接口 `/demo`

| 方法/路径 | 说明 |
| --- | --- |
| `GET /demo/ping` | 正常完成 |
| `GET /demo/reject?reason=` | 业务拒绝（422） |
| `GET /demo/timeout?delayMillis=&timeoutMillis=` | 依赖超时（504） |
| `GET /demo/boom` | 未预期异常（500） |
| `GET /demo/retry?failAttempts=&attemptDelayMillis=&timeoutMillis=` | 前 N 次超时后重试成功，最终 SUCCESS |
| `GET /demo/async` | 线程池异步处理，traceId 跨线程保持 |
| `POST /demo/background` | 提交即返回，后台任务在同一 traceId 下执行 |

### 5.2 本地观测接口 `/observability`

| 方法/路径 | 说明 |
| --- | --- |
| `GET /observability/traces/{traceId}` | 单条链路：最终归类、状态码、耗时、是否上游提供、全部 span 事件、错误类型 |
| `GET /observability/traces?limit=` | 最近已结案链路 |
| `GET /observability/metrics` | 各类计数、成功率、在途数、延迟分位数 |
| `GET /observability/logs/{traceId}` | 属于该 traceId 的结构化日志（含线程名、spanId、异常堆栈） |
| `POST /observability/reset` | 清空链路/指标/日志（测试隔离） |

> 说明：`GET /observability/metrics` 观察到的 `activeRequests` 至少为 1 时，
> 那个 1 通常是**正在执行 metrics 查询的请求自身**（它在自己结案前读取快照），
> 属于自观察现象，并非泄漏；请求结束后即为 0（集成测试在请求外读取，断言为 0）。

---

## 6. 本地验证方法

### 6.1 运行测试

```bash
mvn test
```

共 40 个测试（单元 + Spring MVC 端到端 + 并发），全部通过。
测试通过 `System.out.printf` 打印**每个用例的输入与判定依据**，例如：

```
[E2ETest] 依赖超时 | 输入=delay=300ms budget=50ms | 判定依据=DependencyTimeoutException → 504，事件数=1 | 实际=DEPENDENCY_TIMEOUT, error=...DependencyTimeoutException
[StoreTest] 并发唯一结案 | 判定依据=16 个线程 CAS 同一 AtomicReference | 实际=获胜次数=1, total=1, outcome=DEPENDENCY_TIMEOUT
[ConcurrentTraffic] 输入=192 请求(每类48) | 指标 total=192 success=48 rejected=48 timeout=48 error=48 active=0
```

### 6.2 启动服务手动验证

```bash
mvn spring-boot:run            # 或 mvn package 后 java -jar target/*.jar
```

依次发起请求并带上/不带追踪头：

```bash
# 沿用上游合法标识
curl -i http://localhost:8080/demo/ping -H 'X-Trace-Id: 0af7651916cd43dd8448eb211c80319c'

# 缺失标识：观察响应头里新生成的 32 位 traceId
curl -i http://localhost:8080/demo/ping

# 非法标识（全 0）：被拒绝并重新生成
curl -i http://localhost:8080/demo/ping -H 'X-Trace-Id: 00000000000000000000000000000000'

# 四类结果
curl -i 'http://localhost:8080/demo/reject?reason=xx'
curl -i 'http://localhost:8080/demo/timeout?delayMillis=300&timeoutMillis=50'
curl -i http://localhost:8080/demo/boom
curl -i 'http://localhost:8080/demo/retry?failAttempts=2&timeoutMillis=30'

# 跨线程
curl -i http://localhost:8080/demo/async
curl -i -X POST http://localhost:8080/demo/background
```

用返回的 traceId 在进程内核对“链路 / 指标 / 日志”三者一致：

```bash
T=0af7651916cd43dd8448eb211c80319c
curl http://localhost:8080/observability/traces/$T   # outcome、status、events
curl http://localhost:8080/observability/logs/$T      # 同一 traceId 的全部结构化日志
curl http://localhost:8080/observability/metrics      # 聚合计数与延迟分位数
```

对 `/demo/async` 查看日志，可看到**同一个 traceId 同时出现在 Tomcat 线程与
`trace-async-*` 工作线程**，即跨线程关联成立。

---

## 7. 代码结构

```
trace/
  TraceIds                         标识合法性、W3C 解析、安全随机生成
  TraceContext                     不可变上下文（traceId/spanId/parentSpanId）
  TraceContextHolder               ThreadLocal 栈 + MDC 联动 + AutoCloseable Scope
  ContextPropagatingExecutorService 通用 ExecutorService 包装（传播规则单一实现）
  ContextPropagatingTaskExecutor   Spring MVC 兼容的传播线程池（TaskDecorator）
  TraceFilter                      请求入口：建链路、唯一结案、回写响应头、兜底清理
  AsyncTracingInterceptor          精确识别控制器异步返回值
observability/
  OutcomeCategory                  四类互斥结果
  OutcomeResolver                  归类口径唯一裁决点
  SpanEvent / RequestObservation   过程事件 / 链路只读视图
  ObservationStore(Impl)           CAS 唯一结案、LongAdder 计数、环形延迟采样
  MetricsSnapshot                  指标快照（含 p50/p95/p99）
  ObservabilityProperties          observability.* 配置
logging/
  InMemoryLogAppender              Logback 内存环形缓冲（结构化 Map，按 traceId 检索）
  InMemoryAppenderInstaller        就绪后挂到 ROOT logger
web/
  DemoController                   覆盖全部场景的演示接口
  GlobalExceptionHandler           异常→状态码，并把真实异常回传过滤器裁决
  ObservabilityQueryController     本地链路/指标/日志/重置端点
domain/
  BusinessRejectionException / DependencyTimeoutException
  DependencyService                模拟下游：成功/超时，每次尝试产出 span 事件
```

## 8. 性能与边界

- 热路径仅一次 HashMap 查询、细粒度锁事件追加、结案时一次 CAS 与 LongAdder 加法，无 IO；
- 链路与日志均为有界结构（容量可配，环形淘汰最旧），内存不会无限增长；
- 延迟分位数基于固定容量环形采样近似（默认每类 2048 样本），不在请求路径做排序；
- 内存采集用于单机演示与验证；生产多实例需替换为外部后端，但传播/归类/唯一性的
  代码契约与本实现一致。
