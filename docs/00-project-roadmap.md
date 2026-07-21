# 项目总体路线图

## 1. 项目目标

构建面向绿色能量与无线射频混合供能网络的仿真实验管理与异步任务调度平台。平台提供场景配置、实验提交、状态跟踪、算法执行、结果查询和失败恢复能力，并通过完整测试、部署文档和性能指标形成可用于秋招的软件开发项目。

## 2. 技术路线

```text
第一阶段：Spring Boot单体业务闭环
  Java 17 + Spring Boot + Spring Security + MyBatis + MySQL

第二阶段：可靠异步任务调度
  Redis + RabbitMQ + 幂等控制 + 重试补偿 + 心跳检测

第三阶段：无线通信仿真执行
  Python + PyTorch + GRPO/PPO Worker + 指标与文件回传

第四阶段：工程化与求职材料
  OpenAPI + Docker Compose + 监控 + 压测 + README + 简历表述
```

## 3. 阶段进度

状态约定：`已完成`、`进行中`、`待开始`。

| 阶段 | 状态 | 核心交付物 | 验收标准 |
|---|---|---|---|
| 1. 项目初始化 | 已完成 | Spring Boot工程、统一响应、健康检查、基础测试 | Java 17可以启动；`/api/v1/system/ping`返回成功；基础测试通过 |
| 2. 数据库基础 | 已完成 | Docker MySQL、HikariCP、MyBatis、Flyway、五张核心表 | MySQL容器健康；Flyway校验通过；核心表可以查询 |
| 3. 任务状态机 | 已完成 | `TaskStatus`及合法流转规则 | 状态流转单元测试覆盖成功、失败、取消和重试路径 |
| 4. 用户认证与权限 | 已完成 | 注册、登录、BCrypt、JWT、USER/ADMIN权限、401/403 | 认证端到端测试通过；密码不以明文保存；受保护接口必须携带有效Token |
| 5. 仿真场景管理 | 已完成 | 场景创建、分页查询、详情、更新、软归档、所有权校验 | 用户只能操作本人场景；参数校验、乐观锁和归档规则有测试 |
| 6. 实验任务管理 | 已完成 | 任务创建、查询、取消、重试、快照、幂等键 | 重复请求不创建重复任务；状态只能合法流转；并发更新不覆盖 |
| 7. Java模拟执行与结果 | 已完成 | Java模拟Worker、进度更新、执行记录、结果保存 | 完成“提交—执行—结果查询”闭环；失败任务可重试 |
| 8. Redis与RabbitMQ | 已完成 | Outbox、可靠消息投递、消费者幂等、重试死信、缓存与限流 | 消息重复消费不产生重复结果；发布或消费异常后任务可恢复；缓存失效不破坏业务正确性 |
| 9. Python仿真执行器 | 已完成 | Python/PyTorch Worker、预训练GRPO推理、结果回传 | Java可调度预训练模型评估；吞吐量及完整模型元数据可以入库查询 |
| 10. 工程化与求职材料 | 待开始 | OpenAPI、Docker部署、监控、压测、README、简历和面试材料 | 新环境可按文档启动；关键接口有测试和性能数据；简历表述可被代码与记录支撑 |

## 4. 当前里程碑

当前里程碑：**阶段9已完成真实Java/RabbitMQ/Python/PyTorch/MySQL链路验收，下一步进入阶段10工程化与求职材料**。

阶段5已于2026-07-16完成并通过验收，交付范围包括：

- `POST /api/v1/scenarios`：创建场景；
- `GET /api/v1/scenarios`：分页查询本人场景；
- `GET /api/v1/scenarios/{id}`：查询本人场景详情；
- `PUT /api/v1/scenarios/{id}`：更新场景并进行乐观锁校验；
- `DELETE /api/v1/scenarios/{id}`：软归档未归档场景；
- 从JWT的`user_id`识别当前用户；
- 使用类型化DTO校验无线通信场景参数，持久化为`config_json`；
- 补充端到端测试、API文档、学习记录与面试问答。

验收结果：场景模块3个端到端测试通过；项目全量11个测试通过，0失败、0错误、0跳过。

阶段6已于2026-07-16完成并通过验收：

- 使用`V2`迁移增加场景快照和SHA-256请求摘要；
- 支持任务提交、分页筛选、详情、取消和失败重试；
- 使用`(creator_id, idempotency_key)`联合唯一约束防止并发重复提交；
- 相同键同参数返回原任务，相同键不同参数返回409；
- 使用`lock_version`保护取消和重试等状态变更；
- 任务模块4个端到端测试通过；项目全量15个测试通过，0失败、0错误、0跳过。

阶段7已于2026-07-16完成并通过验收：

- 使用MySQL轮询、`ThreadPoolTaskExecutor`和条件更新实现任务入队、分发与原子抢占；
- 使用`task_execution`保存每次执行尝试、Worker标识、进度心跳、开始结束时间和错误信息；
- 使用固定种子的`JAVA_MOCK`引擎生成可复现的合成吞吐量、AoI和收敛步数，并明确标记为非科研结果；
- 支持运行中协作取消、Worker异常失败、同任务手动重试和心跳超时恢复；
- 在同一事务中提交任务成功、执行成功和结果保存，避免部分成功；
- 提供本人任务结果查询接口，并统一隐藏他人任务是否存在；
- 配置Surefire同时执行`*Test`和`*IT`，项目全量26个测试通过，0失败、0错误、0跳过。

代码学习辅助已于2026-07-16完成：

- 为63个`src/main/java`主代码文件补充中文类、字段、方法和框架注释；
- 对Spring Security、场景CRUD、任务幂等、乐观锁、异步调度、心跳、取消、失败恢复和结果闭环增加原理说明；
- 新增`docs/09-code-reading-guide.md`，提供分层阅读顺序、关键断点和面试复述主线；
- 注释只解释现有实现，不改变任何业务行为，并由全量编译和测试验证。

## 5. 阶段依赖关系

```text
项目初始化
  -> 数据库基础
  -> 用户认证
  -> 场景管理
  -> 任务管理
  -> Java模拟执行与结果
  -> Redis/RabbitMQ可靠异步化
  -> Python真实仿真
  -> 工程化与简历收尾
```

任务状态机已提前完成，后续任务管理直接复用。

## 6. 质量门槛

每个业务模块完成前必须满足：

- 接口输入使用Bean Validation校验；
- 用户数据必须校验所有权，不能通过修改ID访问他人数据；
- 数据库写操作明确事务边界；
- 并发或重复请求有一致性策略；
- 错误响应使用稳定业务错误码，不向前端暴露堆栈；
- 核心成功路径和失败路径有自动化测试；
- 学习日志记录设计原因、问题定位、测试方法和面试参考回答；
- 只有已经实现并验证的内容才能写入简历。

## 7. 已统一的项目口径

1. 任务初始状态使用代码中的`PENDING`，不再使用`CREATED`。
2. 第一版不建立独立算法配置表，算法和训练参数随任务保存；如后续需要复用算法模板，再新增实体和迁移脚本。
3. `GET /api/v1/users/me`是正式用户接口；管理员探针只用于权限测试，不作为正式业务API。
4. 单体应用按业务模块组织，当前已实现`user`、`security`、`scenario`、`task.domain`等边界。

## 8. 阶段8分步计划

阶段8采用“先可靠消息、后Redis优化”的顺序。每个小步骤单独讲解、编码、测试和验收，未通过当前步骤前不进入下一步。

### 8.0：冻结阶段7基线

- 整理并提交当前中文注释、学习日志和路线图；
- 执行`mvn -s .mvn/settings.xml clean test`，确认26个测试仍全部通过；
- 记录Git提交号，保证阶段8出现问题时可以和稳定基线比较。

验收：工作区状态明确，阶段7代码与测试结果可追溯。

完成记录：已在提交`4b38945`冻结阶段7与中文学习注释基线；从空`target`重新编译63个主源码和12个测试源码，全量26个测试通过，0失败、0错误、0跳过。

### 8.1：确认消息模型与RabbitMQ拓扑

- 确认RabbitMQ只替换任务分发，MySQL仍是任务状态和结果的最终事实来源；
- 设计主交换机、执行队列、重试队列、死信交换机和死信队列；
- 定义路由键、消息字段和消息版本；
- 默认消息只保存`eventId + taskId + eventType + schemaVersion + occurredAt`。

推荐拓扑：

```text
simulation.task.exchange
  -- simulation.task.execute --> simulation.task.execute.queue

simulation.task.retry.exchange
  --> simulation.task.retry.queue -- TTL到期 --> simulation.task.exchange

simulation.task.dlx
  --> simulation.task.dead.queue
```

验收：形成独立设计文档，本步骤不写业务代码。

完成记录：已新增`docs/10-rabbitmq-outbox-design.md`，补充`attemptNo`防止旧消息抢占新重试；用户于2026-07-19确认全部采用推荐方案，阶段8.1正式关闭。

### 8.2：接入RabbitMQ基础环境

- 在Docker Compose中增加RabbitMQ及管理界面；
- 引入Spring AMQP依赖和类型化配置；
- 由应用声明交换机、队列、绑定和死信参数；
- 增加最小连接测试，验证应用可启动且拓扑存在。

验收：RabbitMQ健康，管理页面可查看拓扑，原有26个测试不回归。

完成记录：已使用`rabbitmq:4.3.2-management`启动本地Broker并接入Spring AMQP；应用在`dispatch-mode=rabbitmq`时声明3个持久化Direct Exchange、主执行/延迟重试/最终死信3个持久化Classic Queue及3条业务绑定。主队列支持5级优先级，重试队列使用10000毫秒TTL并通过DLX自动返回主执行路由。`RabbitConnectionIT`与`RabbitTopologyIT`均通过，Broker命令行已核验真实资源及参数；全量28个测试通过，0失败、0错误、0跳过。阶段8.2于2026-07-19正式关闭，尚未进入Outbox表和消息发布代码。

当前记录：RabbitMQ 4.3.2基础容器已经通过Compose启动并达到`healthy`；5672、15672、`wireless_sim`虚拟主机、开发用户、管理HTTP接口和D盘命名卷均已验证。Spring AMQP Starter、连接、Confirm、Return、mandatory、手动ACK和监听并发配置已经接入；`RabbitConnectionIT`真实连接成功，全量27个测试通过。下一小步是由应用声明交换机、队列、绑定和死信参数。

### 8.3：建立Outbox并保证任务提交原子性

- 使用新的Flyway迁移创建`outbox_event`，不修改已执行的迁移脚本；
- 任务创建或失败重试时，在同一事务写任务状态和待发布事件；
- 为事件ID、聚合ID、状态及下次发送时间建立约束和索引；
- 增加测试证明任务与Outbox只能同时成功或同时回滚。

验收：不存在“任务已经进入待执行状态但没有可恢复事件”的状态。

完成记录：第1步完成V3迁移，第2步完成Java持久化层，第3步把首次提交与人工重试接入Outbox。第4步新增`TaskOutboxTransactionIT`，仅用`@MockitoBean`让Outbox插入主动失败，任务服务、任务Mapper、MySQL和Spring事务管理器保持真实。测试证明首次提交失败后不存在任务记录；人工重试失败后任务仍为`FAILED`，`retry_count=0`、`lock_version=0`且原错误信息不变，两种情况均无事件落库。定向2项及全量34项测试通过。阶段8.3于2026-07-19完成，达到“任务状态变化与可恢复事件只能同时提交或同时回滚”的验收要求。

### 8.4：实现Outbox可靠发布器

- [x] 第1步：类型安全配置、批量领取、并发隔离和超时租约恢复；
- [x] 第2步：发送单条持久化消息，接入Publisher Confirm与Return；
- [x] 第3步：按确认结果更新成功/失败状态，实现5秒至5分钟指数退避；
- [x] 第4步：增加每秒扫描、每批20条的定时发布器；
- [x] 第5步：模拟Broker不可用、不可路由和状态更新失败并完成验收；
- 批量领取待发布事件，发送到RabbitMQ；
- 开启Publisher Confirm和Return，区分Broker确认、不可路由与网络异常；
- 确认成功后标记事件已发布，失败则记录次数和下次重试时间；
- 允许“已发送但状态未更新”造成重复消息，不允许静默丢消息。

验收：模拟RabbitMQ不可用、不可路由和状态更新失败，事件都能恢复或再次发送。

第1步完成记录：新增`OutboxPublisherProperties`并绑定扫描间隔、批量大小、租约、确认超时和重试延迟；新增`OutboxClaimService`，在短事务内用`FOR UPDATE SKIP LOCKED`锁定可发送事件，再以`claimed_by/claimed_at`标记为`SENDING`并递增发布尝试次数；新增超时租约恢复SQL。领取查询按`next_attempt_at, created_at, id`排序，与发布扫描索引一致。真实MySQL并发测试证明两个发布器各领取不同事件，超时领取可恢复；全量37项测试通过。当前尚未向RabbitMQ发送消息，阶段8.4整体仍在进行中。

第2步完成记录：新增`OutboxMessagePublisher`和结构化发布结果，发送UTF-8 JSON持久化消息，并设置`messageId/correlationId/type/priority/timestamp`及事件、聚合、尝试号、消息版本等Header；使用`CorrelationData`等待最长5秒的Publisher Confirm，并优先检查mandatory Return。结果明确区分`ACK/RETURNED/NACK/TIMEOUT/SEND_FAILED`。5项单元测试覆盖全部分支，真实RabbitMQ测试证明消息进入主执行队列且属性完整；全量43项测试通过。当前尚未根据结果更新Outbox数据库状态，也尚未增加定时扫描器。

第3步完成记录：新增`OutboxRetryBackoffCalculator`和`OutboxPublishResultService`。只有`id/status=SENDING/claimed_by`同时匹配时才能落库；ACK更新为`PUBLISHED`并记录`published_at`，其他结果恢复`PENDING`、写入错误摘要并按5、10、20、40、80、160、300秒封顶退避。时间以MySQL当前时间为基准。错误领取者更新0行，真实MySQL与退避测试全部通过。

第4步完成记录：新增`OutboxPublisherScheduler`，仅在`dispatch-mode=rabbitmq`且`simulation.outbox.enabled=true`时创建。`publishOnce`以fixedDelay方式每秒执行“领取一批、逐条发送、逐条落库”；`recoverExpiredOnce`独立恢复2分钟租约外的`SENDING`事件。领取事务、网络发送和结果事务彼此分离，单条异常不阻断同批其他事件。真实MySQL与RabbitMQ测试验证事件从`PENDING`经`SENDING`进入`PUBLISHED`并到达主执行队列。

第5步完成记录：真实Broker测试验证不可路由消息即使Confirm ACK仍返回`RETURNED`；故障注入验证`TIMEOUT/SEND_FAILED`进入持久化退避；结果落库异常时事件保留`SENDING`并由租约恢复为`PENDING`。并发领取、所有权竞争、NACK、Confirm超时、Broker异常、Return和完整成功闭环均有测试覆盖。全量55项测试通过，0失败、0错误、0跳过。阶段8.4于2026-07-19正式关闭。

### 8.5：实现RabbitMQ消费者并替换任务扫描分发

- [x] 使用`@RabbitListener`接收执行消息，配置手动ACK和合理的prefetch；
- [x] 校验消息版本、载荷和AMQP属性，按任务状态与`attemptNo`分类消费；
- [x] 使用`taskId + QUEUED + retry_count`条件更新和执行记录唯一约束实现幂等；
- [x] 同步复用阶段7的状态机、`task_execution`、Java模拟Worker、心跳、取消和结果事务；
- [x] 成功、明确业务失败、重复和旧消息ACK；永久非法消息Reject；临时异常NACK并重回队列；
- [x] 通过`dispatch-mode`互斥启用MySQL扫描器或RabbitMQ消费者，超时恢复器继续保留。

验收：重复发送同一消息不会产生重复执行记录或结果；两个消费者可以正确分担不同任务。

完成记录：新增版本化消息校验器、消费准备结果、严格执行轮次抢占入口和同步`@RabbitListener`。首次`PENDING`消息先进入`QUEUED`；抢占SQL同时匹配任务ID、`QUEUED`和`retry_count = attemptNo - 1`，并用消息中的准确轮次创建`task_execution`。消费者只在Worker结果可靠落库或幂等吸收后ACK；非法契约、任务不存在和未来轮次Reject；未预期临时异常在阶段8.5暂时NACK并重新入队。真实MySQL/RabbitMQ测试验证成功、重复消息、业务失败、严格并发抢占和两种分发模式互斥。全量71项测试通过，0失败、0错误、0跳过，阶段8.5于2026-07-19正式关闭。默认分发模式仍保留`mysql`，待8.6有限重试和死信闭环完成后再评估切换。

### 8.6：实现有限重试和死信闭环

- [x] 区分临时异常与永久异常，永久异常不做无意义重试；
- [x] 临时异常进入10秒TTL重试队列，总处理次数达到3次后进入最终死信；
- [x] 用`x-delivery-attempt/x-last-error/x-last-failed-at/x-final-failure`记录处理历史；
- [x] 重试/死信转发等待Publisher Confirm并检查Return，成功后才ACK原消息；
- [x] 重试耗尽后条件同步匹配轮次且仍待执行的任务为`FAILED`；
- [x] 提供RabbitMQ管理界面、命令行查看和人工重新驱动流程，不自动无限循环。

验收：临时失败可恢复，永久失败不会形成重试风暴，死信可定位和处理。

完成记录：新增`TaskMessageForwarder`，复制原消息并可靠发布到重试或最终死信交换机，使用Publisher Confirm、mandatory Return和5秒确认超时决定是否可以ACK原消息。首次消息按第1次处理，临时异常在未达到3次时写入下一次`x-delivery-attempt`并进入10秒TTL队列，TTL到期由RabbitMQ自动回到主队列；第3次临时失败或永久契约错误进入最终死信队列。新增`TaskMessageFailureService`，只把相同`attemptNo`且仍为`PENDING/QUEUED`的任务条件更新为`FAILED`，不覆盖`RUNNING`和业务终态。单元测试、真实MySQL测试、真实RabbitMQ重试/TTL回流/最终死信测试及全量回归全部通过：88项测试，0失败、0错误、0跳过。阶段8.6于2026-07-19正式关闭。可靠消息闭环验收完成后，生产默认`dispatch-mode`已切换为`rabbitmq`；`mysql`模式继续保留为显式回退方案。

### 8.7：按明确场景引入Redis

- [x] 第0步：确认Redis版本、缓存TTL、限流阈值、故障降级和数据持久化策略；
- [x] 第1步：在Compose中接入固定版本Redis，增加Spring Data Redis依赖、类型安全配置和真实连接测试；
- [x] 第2步：缓存本人任务详情，使用`userId + taskId`组成隔离键，采用Cache Aside与短TTL；
- [x] 第3步：任务取消、重试、执行状态和进度变化时，在数据库事务成功提交后删除对应缓存；
- [x] 第4步：使用Redis Lua脚本原子完成用户级任务提交计数与TTL设置，超过阈值返回HTTP 429；
- [x] 第5步：验证缓存命中/回源、并发限流、Redis不可用时的降级，并补充学习日志和面试问答。

已确认并实现的第一版参数：任务详情缓存TTL为5秒；单用户60秒内最多提交5次；Redis不可用时缓存直接回源MySQL、限流采用Fail Open；Redis只保存可重建的缓存和短期计数，不开启持久化；测试环境默认关闭Redis，专项集成测试显式启用真实Redis。

本阶段继续遵守以下边界：MySQL是任务事实来源；Redis不保存唯一业务状态；暂不引入Redis分布式锁，因为数据库条件抢占已解决任务执行互斥；列表查询第一版不缓存，避免分页、状态和算法组合造成大量缓存键及复杂失效。

验收：缓存命中减少热点查询；缓存删除或Redis重启后数据可由MySQL恢复；限流规则有自动化测试。

完成记录：使用`redis:7.4.2-alpine`在宿主机`16379`端口提供无持久化缓存服务；接入Spring Data Redis与Lettuce，使用类型安全配置管理开关、5秒TTL、60秒窗口和5次阈值。`TaskDetailCache`显式实现Cache Aside，缓存键包含用户ID和任务ID；缓存未命中、内容损坏或Redis连接异常时回源MySQL，并在连接失败后本地退避5秒。任务取消、重试、入队、抢占、进度、成功、失败和超时恢复均在MySQL事务提交后删除缓存。`TaskSubmissionRateLimiter`使用Lua原子执行`INCR + PEXPIRE`，只有新任务消耗额度，幂等重放不计数，超限返回429，Redis不可用时Fail Open。真实Redis/MySQL测试覆盖连接、缓存、提交后失效、用户隔离、TTL、幂等、并发限流和不可用端口降级。阶段8.7全量93项测试通过，0失败、0错误、0跳过，于2026-07-19正式关闭。

### 8.8：故障测试、文档与阶段验收

- [x] 测试重复消息、乱序消息、RabbitMQ重启、消费者异常、ACK前异常窗口、Redis不可用和多消费者并发；
- [x] 核对任务、执行记录、结果和Outbox之间不存在孤立或矛盾状态；
- [x] 更新架构、数据模型、API说明、学习日志、调试指南和面试问答；
- [x] 记录阶段8全量测试数量和可复现的演示步骤。

验收：达到“至少一次投递、业务幂等、异常可恢复、缓存可降级”的项目口径。

完成记录：新增Redis损坏JSON自动删除、回源MySQL并重建缓存的专项测试；扩展事务测试，证明Outbox插入失败导致MySQL回滚时，`afterCommit`缓存删除不会误执行。新增`StageEightEndToEndIT`，使用真实MySQL、Redis和RabbitMQ，从任务提交开始验证`experiment_task -> outbox_event -> RabbitMQ -> task_execution -> simulation_result`完整闭环，并核对Outbox为`PUBLISHED`、任务为`SUCCEEDED/100%`、仅一条执行记录和结果、心跳完整、旧缓存失效后可重建。实际重启Redis后临时缓存键按无持久化设计消失；实际重启RabbitMQ后3个持久化队列仍存在，连接和拓扑测试恢复。最终全量95项测试通过，0失败、0错误、0跳过；MySQL无测试孤儿记录、Redis无项目测试键、RabbitMQ三个队列均为0待处理/0未确认。阶段8.8及阶段8于2026-07-20正式关闭，详细验收见`docs/11-stage8-reliability-acceptance.md`。

## 9. 阶段8开始前需要确认的关键决策

当前推荐方案如下，开始对应编码步骤前仍会向用户展示并确认：

1. RabbitMQ只负责分发任务事件，MySQL继续作为最终事实来源；
2. 使用Transactional Outbox，不在任务事务中直接发送RabbitMQ；
3. 消息使用轻量引用结构，不携带完整仿真参数；
4. 消费采用At-Least-Once、手动ACK和数据库幂等，不宣称端到端Exactly Once；
5. 使用有限延迟重试与死信队列，不做无限重试；
6. Redis第一版只做任务热点缓存和用户提交限流，不做任务事实存储或分布式锁；
7. 先完成RabbitMQ完整闭环，再接入Redis，避免同时引入两个基础设施导致问题难以定位。

## 10. 阶段9最小预训练模型评估计划

阶段9采用“只推理、不在线训练”的最小范围，优先完成可用于简历展示的Java/Python跨语言模型评估闭环。

### 9.1：整理独立GRPO推理程序

- [x] 导入现有RSMA环境、GRPO模型结构、评估代码和本地可信权重；
- [x] 移除评估入口中的C盘绝对路径，支持CLI参数和环境变量；
- [x] 保留原有3设备、10回合、100步、确定性评估、seed 2026及全部物理参数默认值；
- [x] 支持传入时隙、数据到达率、绿色能量、电池、缓存、WPT和设备发射功率；
- [x] 限制第一版只支持3设备RSMA，天线数暂不参与模型计算；
- [x] 输出标准`summary.json`，只返回吞吐量并将AoI设为`null`；
- [x] 修复环境reset未应用回合seed的问题；
- [x] 增加兼容性和真实权重可复现测试。

完成记录：评估入口可从仓库根目录运行，不依赖IDE的`PYTHONPATH`；可信权重使用受限反序列化加载并记录SHA-256。标准JSON记录模型ID、环境和输入输出维度、评估模式、场景参数与单位、吞吐量均值/标准差/极值、AoI空值和产物路径。真实CUDA评估成功，显式传入原默认场景参数运行成功；3项Python测试全部通过，相同权重、场景和seed连续评估的吞吐量业务指标完全一致。设计与运行方法见`docs/12-stage9-grpo-inference-design.md`和`python-worker/README.md`。

### 9.2：定义Java/Python任务契约

- [x] 将接口精简为领取、成功结果和失败结果三个短请求，不引入逐回合进度API；
- [x] 固化Java场景字段到Python环境字段的映射与单位；
- [x] 定义模型不兼容、权重不存在、权重加载失败和推理失败错误码；
- [x] 确认Python不直接写MySQL，由Java维护状态和结果事务。

### 9.3—9.5：跨语言闭环

- [x] 9.3：实现Java内部Worker API和Worker Token认证；
- [x] 9.4：实现Python RabbitMQ消费者并调用独立推理函数；
- [x] 9.5：验证重复领取、重复完成、单位映射、AoI空值和原有功能回归。

阶段9完成记录：Java新增`simulation.execution.worker-mode`，`java-mock`与`python`模式互斥，防止两个消费者竞争同一主队列。内部API使用`X-Worker-Token`，未配置令牌时默认关闭；领取前校验GRPO、THROUGHPUT、RSMA和3设备边界，成功后返回带显式单位的不可变快照。Python消费者使用`prefetch=1`、手动ACK、有限重试和死信，直接复用9.1推理函数；Java在同一事务内保存任务终态、执行终态、吞吐量与模型追踪信息，AoI保持NULL。最终Java全量97项测试和Python 10项测试全部通过，真实GRPO权重CUDA复现测试通过。设计、契约和验收分别见`docs/12-stage9-grpo-inference-design.md`、`docs/13-python-worker-api-contract.md`和`docs/14-stage9-acceptance.md`。

### 9.6：结果查询与真实链路收尾

- [x] 结果查询兼容JAVA_MOCK和预训练GRPO两种异构`metrics_json`；
- [x] 保留旧版JAVA_MOCK响应字段，同时返回完整`metrics`对象；
- [x] Python RabbitMQ心跳支持环境变量覆盖，并在连接已关闭时安全退出；
- [x] Java RabbitMQ心跳和JWT有效期支持环境变量覆盖，默认值仍为30秒和30分钟；
- [x] 使用真实基础设施和真实GRPO权重手工验证四张业务表与RabbitMQ队列状态闭环；
- [x] 补充JAVA_MOCK兼容、GRPO元数据和Python心跳配置自动化测试。

完成记录：手工链路依次观察到`experiment_task`从PENDING进入RUNNING并最终SUCCEEDED/100%、`outbox_event`成功发布、`task_execution`形成唯一成功执行记录、`simulation_result`形成唯一结果且吞吐量有值/AoI为空；RabbitMQ最终Ready=0、Unacked=0。断点调试期间发现Pika心跳中断及重复关闭连接问题，最终采用“生产默认30秒、调试环境变量覆盖、安全判断连接状态后关闭”的方案。阶段9至此正式关闭。
