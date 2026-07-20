# 代码阅读指南

## 1. 建议阅读顺序

不要按文件名从头到尾背代码，先沿一条真实请求链路阅读：

```text
HTTP请求
  -> api：接收、校验参数，提取登录用户
  -> application：编排业务规则和事务
  -> domain：保存状态、枚举和计算规则
  -> infrastructure：执行SQL、调度线程和访问外部资源
  -> MySQL
```

推荐依次阅读：

1. `AuthenticationController -> AuthenticationService -> UserMapper`：理解注册、登录和JWT签发；
2. `ScenarioController -> ScenarioService -> ScenarioMapper`：理解CRUD、所有权校验和乐观锁；
3. `TaskController -> TaskService -> TaskMapper`：理解快照、幂等、取消和重试；
4. `SimulationTaskDispatcher -> SimulationTaskWorker`：理解轮询、线程池和异步执行；
5. `TaskExecutionClaimService -> TaskExecutionRuntimeService -> TaskExecutionLifecycleService`：理解抢占、心跳和状态闭环；
6. `JavaMockSimulationEngine -> SimulationResultMapper`：理解合成结果和结果落库。
7. `TaskService -> OutboxEventMapper -> OutboxPublisherScheduler`：理解任务与事件同事务、异步可靠发布和失败退避；
8. `TaskExecutionMessageListener -> TaskMessagePreparationService -> SimulationTaskWorker`：理解手动ACK、消息幂等、尝试号和执行闭环；
9. `TaskDetailCache -> TaskCacheInvalidationService -> TaskSubmissionRateLimiter`：理解Cache Aside、提交后失效、故障降级和Lua原子限流。

## 2. 四层目录分别解决什么问题

- `api`：HTTP协议层。重点看路径、请求体、参数校验、状态码和响应DTO。
- `application`：用例层。重点看业务顺序、事务边界、异常和并发规则。
- `domain`：领域层。重点看实体字段、状态枚举、合法流转和纯计算逻辑。
- `infrastructure`：基础设施层。重点看MyBatis、SQL、线程池、定时任务和持久化。

同一模块使用相同四层目录，是为了让“业务规则”和“技术实现”各归其位，不是机械重复。

## 3. 第一轮调试建议断点

### 登录链路

- `AuthenticationController.login`
- `AuthenticationService.login`
- `JwtTokenService.issue`

观察：请求DTO、密码哈希比对、JWT Claims和响应Token。

### 提交任务链路

- `TaskService.submit`
- `TaskService.requestHash`
- `TaskMapper.insert`对应XML SQL

观察：幂等键、请求摘要、场景快照、任务初始状态和数据库回填主键。

### 异步执行链路

- `SimulationTaskDispatcher.dispatchOnce`
- `TaskExecutionClaimService.claimQueuedTask`
- `SimulationTaskWorker.executeClaimed`
- `TaskExecutionRuntimeService.updateProgressAndHeartbeat`
- `TaskExecutionLifecycleService.completeSuccessfully`

观察：`PENDING -> QUEUED -> RUNNING -> SUCCEEDED`状态变化、线程名、执行记录、心跳和结果表。

### RabbitMQ可靠消息链路

- `TaskService.createOutboxEvent`
- `OutboxClaimService.claimBatch`
- `OutboxMessagePublisher.publish`
- `OutboxPublishResultService.recordResult`
- `TaskExecutionMessageListener.onMessage`
- `TaskMessageForwarder.forwardToRetry/forwardToDeadLetter`

观察：任务与Outbox是否同事务提交、`PENDING -> SENDING -> PUBLISHED`、Confirm与Return结果、`deliveryTag`、`attemptNo`、ACK时机和重复消息分类。

### Redis缓存与限流链路

- `TaskService.get`
- `TaskDetailCache.get/put/evict`
- `TaskCacheInvalidationService.evictAfterCommit`
- `TaskSubmissionRateLimiter.acquireOrThrow`

观察：缓存键中的用户隔离、5秒TTL、脏JSON自愈、事务提交后回调，以及Lua返回的当前窗口计数。

## 4. 必须能解释的核心问题

1. JWT为什么适合无状态API，签名与加密有什么区别？
2. 为什么用户密码使用BCrypt而不是可逆加密？
3. 为什么场景查询SQL必须同时携带资源ID和ownerId？
4. 乐观锁的`version`如何防止并发覆盖？
5. 幂等键和请求摘要分别解决什么问题？
6. 为什么提交任务时要保存场景参数快照？
7. 多个Worker如何保证同一任务只被一个实例抢占？
8. 为什么进度和心跳必须在同一事务中更新？
9. 运行中取消为什么采用协作式取消？
10. Worker宕机后，心跳超时恢复如何避免误判？
11. 为什么`JAVA_MOCK`结果不能当作GRPO/PPO科研结论？
12. Transactional Outbox解决了哪一个“双写”问题？
13. 为什么RabbitMQ至少一次投递必须配合数据库业务幂等？
14. Publisher Confirm ACK为什么仍要检查Return？
15. 为什么Redis不可用不能阻断任务查询和提交？

这些问题的详细回答继续保存在`docs/03-learning-log.md`。

## 5. 注释的正确使用方式

中文注释用于帮助你建立第一遍理解，不建议逐字背诵。每读完一个方法，应尝试脱离注释回答三件事：

1. 输入是什么；
2. 它改变了哪些状态；
3. 失败时如何保证数据一致。

能够用自己的话讲清楚调用链，再结合调试器观察变量变化，才算真正掌握代码。
