# 阶段8.1：RabbitMQ与Transactional Outbox设计

状态：**已确认并完成**。

## 1. 本阶段解决什么问题

阶段7已经用MySQL轮询和本地线程池打通完整执行闭环。阶段8不修改无线通信模拟算法，也不推翻任务状态机，而是把“如何把待执行任务交给Worker”升级为可靠消息分发：

```text
阶段7：MySQL任务表 -> 定时扫描业务任务 -> 本地线程池 -> Java Worker

阶段8：MySQL任务表 + Outbox -> RabbitMQ -> 消费者 -> Java Worker
```

目标：

- 任务事务成功后，待发送事件一定可以恢复，避免数据库与RabbitMQ双写不一致；
- 多个消费者由RabbitMQ分发消息，不再由每个实例持续扫描全部业务任务；
- 允许消息至少投递一次，通过业务幂等避免重复执行和重复结果；
- 对临时投递异常进行有限延迟重试，对永久异常进入死信队列；
- 保留阶段7已经验证的状态机、执行记录、心跳、取消、结果事务和超时恢复。

非目标：

- 不宣称端到端Exactly Once；
- 不在RabbitMQ中保存完整场景快照或训练参数；
- 不使用RabbitMQ自动重跑已经失败的仿真实验；
- 本步骤不引入Redis，Redis在消息闭环完成后单独实现；
- 阶段8仍使用`JAVA_MOCK`，真实Python/PyTorch Worker属于阶段9。

## 2. 已确定的设计原则

1. MySQL是任务、执行记录和结果的最终事实来源。
2. RabbitMQ只分发“请执行某次任务尝试”的事件。
3. 任务与Outbox事件在同一个MySQL事务中写入。
4. Outbox发布采用Publisher Confirm、`mandatory=true`和Publisher Return。
5. 消费者使用手动ACK；消息可能重复，但有效业务结果不能重复。
6. 消费者继续依靠数据库条件更新和唯一约束抢占任务，不增加Redis分布式锁。
7. 消息投递重试与用户业务重试是两套计数，不能混用。
8. RabbitMQ模式稳定前保留MySQL模式作为可配置回退，但同一时刻只能启用一种正常分发方式。

## 3. 端到端流程

### 3.1 首次提交

```text
客户端提交任务
  -> MySQL本地事务开始
     -> 插入experiment_task，状态PENDING，retry_count=0
     -> 插入outbox_event，attempt_no=1，状态PENDING
  -> 本地事务提交
  -> HTTP返回202

Outbox发布器
  -> 领取待发布事件
  -> 向RabbitMQ发送持久化消息
  -> 收到Broker Confirm且消息没有被Return
  -> 标记outbox_event为PUBLISHED

RabbitMQ消费者
  -> 校验消息结构、版本和attemptNo
  -> PENDING任务条件更新为QUEUED
  -> QUEUED任务按taskId + attemptNo原子抢占为RUNNING
  -> 插入task_execution
  -> 同步调用阶段7 Java Worker
  -> Worker持久化SUCCEEDED / FAILED / CANCELLED结果
  -> 消费者手动ACK
```

首次提交仍返回`PENDING`。消息到达消费者后才进入`QUEUED/RUNNING`，因此`PENDING`可以解释为“系统已经受理，可靠事件正在等待发布或消费”。

### 3.2 用户手动重试

```text
客户端请求重试FAILED任务
  -> 同一MySQL事务
     -> experiment_task：FAILED -> QUEUED，retry_count + 1
     -> 新增outbox_event，attempt_no = 更新后的retry_count + 1
  -> 返回更新后的任务
```

例如：

- 初次执行：`retry_count=0`，`attemptNo=1`；
- 第一次人工重试：`retry_count=1`，`attemptNo=2`；
- 第二次人工重试：`retry_count=2`，`attemptNo=3`。

### 3.3 取消与延迟消息

- 用户在`PENDING/QUEUED`时取消，延迟到达的执行消息无法通过状态条件，消费者将其视为无效旧消息并ACK；
- 用户在`RUNNING`时取消，继续复用阶段7协作取消；
- 已经`SUCCEEDED/FAILED/CANCELLED`的任务收到重复消息，不创建新执行记录，直接ACK；
- 取消任务不需要删除Outbox或RabbitMQ中的消息，正确性由数据库最终状态保证。

## 4. 为什么消息必须携带attemptNo

只有`eventId + taskId`还不够。

假设第一次执行消息发生延迟重复：

1. 尝试1已经失败；
2. 用户发起重试，任务重新变为`QUEUED`，准备尝试2；
3. 尝试1的旧消息此时再次到达；
4. 如果消费者只检查`taskId + QUEUED`，旧消息可能错误地抢占尝试2。

因此消息必须包含`attemptNo`，抢占SQL同时验证：

```text
任务当前状态允许执行
AND retry_count + 1 = message.attemptNo
```

这样尝试1的旧消息无法抢占尝试2。`eventId`负责消息追踪，`attemptNo`负责业务执行轮次隔离，两者职责不同。

## 5. 消息结构

事件类型第一版只有：

```text
TASK_EXECUTION_REQUESTED
```

JSON消息示例：

```json
{
  "eventId": "d61f950a-6ddb-4c90-8fe2-c74079b6a9cd",
  "taskId": 123,
  "attemptNo": 1,
  "eventType": "TASK_EXECUTION_REQUESTED",
  "schemaVersion": 1,
  "occurredAt": "2026-07-19T08:30:00.000Z"
}
```

字段说明：

| 字段 | 用途 |
|---|---|
| `eventId` | 全局唯一事件ID，同时作为AMQP `messageId`和Publisher Confirm关联ID |
| `taskId` | 查询MySQL任务快照和状态 |
| `attemptNo` | 隔离初次执行和各次业务重试，阻止旧消息抢占新尝试 |
| `eventType` | 支持以后扩展其他事件类型 |
| `schemaVersion` | 消息结构版本，消费者遇到不支持版本时进入死信 |
| `occurredAt` | 事件在业务事务中产生的UTC时间，只用于追踪，不用于决定任务状态 |

不在消息中保存：用户身份、完整场景快照、训练参数和结果。消费者以MySQL任务快照为准，避免大消息及消息内容与数据库事实分叉。

AMQP消息属性：

- `messageId = eventId`；
- `contentType = application/json`；
- `deliveryMode = PERSISTENT`；
- `type = TASK_EXECUTION_REQUESTED`；
- `correlationId = taskId:attemptNo`；
- `priority`由业务优先级`0—10`映射到队列优先级`0—5`。

## 6. RabbitMQ拓扑

全部交换机、队列和绑定均为durable；消息使用persistent。开发环境采用经典持久化队列，生产集群可根据节点数再评估Quorum Queue，本地单节点不伪装高可用。

| 类型 | 名称 | 类型/作用 |
|---|---|---|
| 主交换机 | `simulation.task.exchange` | direct，接收执行事件 |
| 主路由键 | `simulation.task.execute` | 路由到主执行队列 |
| 主队列 | `simulation.task.execute.queue` | Worker正常消费；`x-max-priority=5` |
| 重试交换机 | `simulation.task.retry.exchange` | direct，接收需要延迟重试的消息 |
| 重试路由键 | `simulation.task.retry` | 路由到重试队列 |
| 重试队列 | `simulation.task.retry.queue` | 无消费者；TTL到期后死信回主交换机 |
| 死信交换机 | `simulation.task.dlx` | direct，接收永久失败或超过次数上限的消息 |
| 死信路由键 | `simulation.task.dead` | 路由到最终死信队列 |
| 死信队列 | `simulation.task.dead.queue` | 保存等待人工检查的消息，不自动循环 |

拓扑：

```text
Outbox Publisher
   |
   | simulation.task.execute
   v
simulation.task.exchange
   |
   v
simulation.task.execute.queue
   |
   v
RabbitMQ Consumer -> Java Worker

临时投递异常：
Consumer -> simulation.task.retry.exchange
         -> simulation.task.retry.queue
         -> TTL到期
         -> simulation.task.exchange
         -> simulation.task.execute.queue

永久异常或达到投递上限：
Consumer -> simulation.task.dlx
         -> simulation.task.dead.queue
```

当前版本采用10秒重试延迟和最多3次消息处理重试，未引入依赖监控数据调优的分级退避队列。

## 7. Outbox设计边界

`outbox_event`与`experiment_task`分表。它至少需要表达：

- 事件ID和事件类型；
- 聚合类型与任务ID；
- 执行尝试号；
- JSON载荷与消息优先级；
- `PENDING/SENDING/PUBLISHED`发布状态；
- 发布尝试次数、下次尝试时间、最后错误；
- 领取者、领取时间、创建时间和发布时间。

建议唯一约束：

```text
(aggregate_type, aggregate_id, event_type, attempt_no)
```

它保证同一个任务尝试最多产生一个执行请求事件。具体字段类型、索引和领取SQL在8.3编码前单独展示确认。

多实例发布器不能长期占用数据库事务等待网络：

1. 短事务批量领取事件并标记`SENDING`；
2. 事务结束后发送RabbitMQ；
3. Confirm成功后用新事务标记`PUBLISHED`；
4. 发布失败则记录错误并安排下次尝试；
5. `SENDING`超过租约时间仍未完成的事件可恢复为待发送。

如果RabbitMQ已经接收消息，但应用在标记`PUBLISHED`前宕机，Outbox会重复发布。该窗口无法由本地事务消除，因此消费者必须幂等。

## 8. 发布可靠性

发布器必须同时判断：

1. Publisher Confirm是否为ACK：表示Broker已经接管消息；
2. 消息是否被Return：表示交换机存在，但没有匹配队列；
3. 是否发生连接、通道或超时异常。

只有“Confirm ACK并且没有Return”才标记Outbox为`PUBLISHED`。发送时设置`mandatory=true`，否则无法路由的消息可能被静默丢弃。

Publisher Confirm与消费者ACK互相独立：前者只确认“生产者到Broker”，后者确认“Broker到消费者并完成应用处理”。

## 9. 消费者、ACK与幂等

阶段8的Java消费者同步调用`SimulationTaskWorker`，由RabbitMQ监听容器的并发数控制同时执行数量，第一版建议：

- `concurrency=2`；
- `prefetch=1`；
- 手动ACK；
- 不再把MQ消息二次塞入无界或不可追踪的本地队列。

处理规则：

| 场景 | 处理方式 |
|---|---|
| 合法消息且任务可抢占 | 执行Worker；任务形成`SUCCEEDED/FAILED/CANCELLED`持久化结果后ACK |
| 重复消息、旧attemptNo、任务已终态 | 不重复执行，直接ACK |
| 不支持的schemaVersion、字段非法 | 发送最终死信，确认死信发布后ACK原消息 |
| MySQL短暂不可用、连接异常 | 发送延迟重试，确认重试消息发布后ACK原消息 |
| 重试达到上限 | 发送最终死信，确认后ACK原消息 |
| 重试/死信重新发布本身失败 | 不ACK原消息，使RabbitMQ保留或重新投递 |

可靠重新发布仍可能重复：如果重试消息已经被Broker确认，但消费者在ACK原消息之前宕机，原消息会再次投递。数据库幂等负责吸收重复。

执行幂等的存储层防线：

1. 抢占SQL同时匹配`taskId + 状态 + attemptNo`；
2. `task_execution`的`(task_id, attempt_no)`唯一约束；
3. 成功结果对同一任务保持唯一；
4. 状态已经变化时重复消息只做ACK，不重新执行。

## 10. 两类重试必须区分

### 消息投递重试

- 解决RabbitMQ、网络、反序列化外围设施或数据库暂时不可用；
- 使用消息头记录次数；
- 不增加`experiment_task.retry_count`；
- 不创建新的`task_execution.attempt_no`；
- 次数耗尽进入死信队列。

### 业务执行重试

- 解决某次仿真已经明确执行失败；
- 当前仍由用户调用`POST /api/v1/tasks/{id}/retry`触发；
- 增加`experiment_task.retry_count`；
- 产生新的`attemptNo`、新的Outbox事件和新的`task_execution`；
- 不由RabbitMQ自动无限重跑算法。

如果Worker已经把任务和执行记录可靠标记为`FAILED`，消费者应ACK原消息。因为消息已完成业务处理，只是业务结果为失败；再次投递同一消息不是正确的业务重试方式。

## 11. 与阶段7的切换方案

增加显式模式配置：

```yaml
simulation:
  execution:
    dispatch-mode: rabbitmq
```

可选值：

- `mysql`：启用阶段7 `SimulationTaskDispatcher`；
- `rabbitmq`：启用Outbox发布器和RabbitMQ消费者；
- 测试可按测试目标关闭外部调度。

在8.2—8.4期间默认保持`mysql`，确保RabbitMQ基础设施尚未完成时现有功能不回归。阶段8.6完成有限重试与最终死信闭环后，生产默认已切换为`rabbitmq`。禁止两个正常分发器同时启用；心跳超时恢复器在两种模式下都继续启用。

阶段7本地线程池代码保留作为MySQL回退模式；RabbitMQ模式的并发由监听容器控制。

## 12. 故障结果表

| 故障位置 | 结果与恢复方式 |
|---|---|
| 任务插入成功前事务失败 | 任务和Outbox一起回滚 |
| 任务已提交，RabbitMQ不可用 | Outbox保持待发送，稍后重试 |
| Broker接收消息，Confirm丢失 | Outbox可能重复发布，消费者幂等 |
| 消息无法路由 | Publisher Return，Outbox不标记成功 |
| 消费者收到重复消息 | attemptNo、状态条件和唯一约束阻止重复执行 |
| 消费者执行中进程退出 | 未ACK消息会重新投递；数据库心跳恢复器最终把失联执行标记失败 |
| Worker明确执行失败 | 任务和执行记录为FAILED，消息ACK；用户可发起新的业务重试 |
| 重试消息超过上限 | 进入最终死信队列，等待检查 |
| 用户已取消但消息后来到达 | 状态条件拒绝执行，消息ACK |

## 13. 阶段8.1验收清单

- [x] 明确MySQL、Outbox与RabbitMQ的职责边界；
- [x] 明确主队列、重试队列和死信队列拓扑；
- [x] 定义版本化消息结构；
- [x] 使用`attemptNo`解决旧消息抢占新重试的问题；
- [x] 明确Publisher Confirm、Return和消费者ACK边界；
- [x] 明确消息重试与业务重试的区别；
- [x] 明确阶段7调度器的配置化切换方式；
- [x] 用户确认关键决策后进入8.2。

## 14. 进入8.2前需要确认

1. 接受消息增加`attemptNo`字段；
2. 接受主队列使用5级优先级，由现有业务优先级`0—10`映射；
3. 接受消息重试10秒、最多3次，业务失败仍由用户手动重试；
4. 接受RabbitMQ模式直接使用2个监听线程、`prefetch=1`，不再经过阶段7本地线程池；
5. 接受通过`dispatch-mode=mysql/rabbitmq`切换，消息闭环验收前默认仍为`mysql`；
6. 接受本地单节点使用经典持久化队列，不把它描述成RabbitMQ高可用集群。

确认记录：用户于2026-07-19确认以上6项全部采用推荐方案，阶段8.1正式关闭。

## 25. 阶段8.5：RabbitMQ消费者与数据库幂等设计

状态：**已实现并通过全量71项测试，阶段8.5正式关闭。**

### 25.1 五个实施小步骤

1. 定义消费准备结果与消息契约校验，明确哪些消息可执行、可直接ACK或必须拒绝。
2. 增加严格执行轮次抢占：SQL同时匹配`task_id`、`QUEUED`和`retry_count = attemptNo - 1`。
3. 实现`@RabbitListener`同步调用Java Worker，并在业务结果落库后手动ACK。
4. 通过`dispatch-mode=mysql/rabbitmq`互斥启用旧扫描调度器和RabbitMQ消费者，超时恢复器在两种模式中均保留。
5. 覆盖合法、重复、并发、旧轮次、未来轮次、取消、非法消息、业务失败和临时异常测试，并更新路线图与学习记录。

### 25.2 消费线程与ACK边界

RabbitMQ监听线程直接、同步调用`SimulationTaskWorker`，不再把消息二次提交到阶段7的本地线程池。监听容器的`concurrency=2`控制并行Worker数量，`prefetch=1`限制每条消费线程同一时刻只预取一条未确认消息。

```text
RabbitMQ监听线程
  -> 校验消息契约
  -> 判断任务状态和attemptNo
  -> 严格条件抢占并创建task_execution
  -> 同步执行Java Worker
  -> 持久化SUCCEEDED / FAILED / CANCELLED
  -> basicAck
```

如果ACK发送失败，RabbitMQ可能重新投递同一消息；数据库状态条件和`task_execution(task_id, attempt_no)`唯一约束负责吸收重复投递。

### 25.3 消费准备结果

| 结果 | 含义 | 处理 |
|---|---|---|
| `READY_TO_EXECUTE` | 消息轮次与当前任务匹配，可尝试抢占 | 同步调用Worker，返回后ACK |
| `ALREADY_HANDLED` | 同一轮已运行、完成、失败或取消 | 不重复执行，ACK |
| `STALE_ATTEMPT` | 消息轮次小于当前期望轮次 | ACK |
| `FUTURE_ATTEMPT` | 消息轮次大于当前期望轮次，数据链路不一致 | 阶段8.5直接拒绝且不重回队列 |
| `TASK_NOT_FOUND` | 消息引用的任务不存在 | 阶段8.5直接拒绝且不重回队列 |

首次提交的任务可能仍为`PENDING`。准备服务先以条件更新把它变为`QUEUED`；人工重试产生事件时任务已经是`QUEUED`，无需重复入队。

### 25.4 attemptNo严格规则

消息必须满足：

```text
message.attemptNo = experiment_task.retry_count + 1
```

最终抢占SQL还必须满足：

```text
id = taskId
AND status = 'QUEUED'
AND retry_count = attemptNo - 1
```

抢占成功后使用消息中的准确`attemptNo`创建执行记录，不在抢占后重新推测轮次。即使两个消费者并发处理同一条消息，也只有一个条件更新能影响1行，另一个返回未抢占并安全ACK。

### 25.5 消息契约校验

消费者校验JSON可反序列化，并校验`eventId`、正数`taskId`、正数`attemptNo`、固定事件类型`TASK_EXECUTION_REQUESTED`、`schemaVersion=1`和非空`occurredAt`。同时核对AMQP属性中的`messageId`、`type`及关键Header与载荷一致，避免消息属性和JSON表达不同事实。

### 25.6 阶段8.5 ACK/NACK规则

| 场景 | RabbitMQ动作 | 原因 |
|---|---|---|
| Worker成功并保存结果 | `basicAck` | 业务处理完成 |
| Worker明确失败且FAILED已落库 | `basicAck` | 业务结果是失败，不等于消息处理失败 |
| 重复、旧轮次、终态或取消 | `basicAck` | 幂等吸收，不应再次执行 |
| JSON非法、字段非法、不支持版本 | `basicReject(requeue=false)` | 永久契约错误，重试无意义 |
| 任务不存在、未来轮次 | `basicReject(requeue=false)` | 当前视为永久数据不一致 |
| 数据库连接等未预期临时异常 | `basicNack(requeue=true)` | 阶段8.5临时保留重新入队能力 |

`basicNack(requeue=true)`可能无限重投，因此只作为阶段8.5的过渡策略。阶段8.6将其替换为有次数上限的TTL重试队列和最终死信队列。

### 25.7 模式互斥

- `enabled=true`且`dispatch-mode=mysql`：启用阶段7 `SimulationTaskDispatcher`，不创建RabbitMQ消费者。
- `enabled=true`且`dispatch-mode=rabbitmq`：启用Outbox发布器和RabbitMQ消费者，不创建数据库任务扫描调度器。
- `TaskTimeoutRecoveryScheduler`在两种分发模式下均保留，用于恢复失联的RUNNING执行。
- 默认模式在阶段8.5验收完成前仍保持`mysql`，避免基础设施未启动时影响现有开发流程。

### 25.8 验收标准

- 合法消息形成唯一执行记录和结果并ACK；
- 重复投递不产生第二条执行记录或第二份结果；
- 两个消费者并发竞争同一任务只有一个抢占成功；
- 旧轮次、终态和已取消任务直接ACK；未来轮次和非法消息被拒绝；
- Worker业务失败可靠落为FAILED后ACK；未预期临时异常NACK并重新入队；
- MySQL模式没有RabbitMQ消费者，RabbitMQ模式没有旧扫描调度器。

### 25.9 全量回归发现的SKIP LOCKED测试边界

阶段8.5全量回归稳定复现了阶段8.4并发测试中的一个过强假设：两个发布器同时执行`FOR UPDATE SKIP LOCKED`时，其中一个发布器允许在当前锁竞争窗口暂时得到空批次；这不表示事件丢失，生产调度器会在下一轮扫描中领取剩余事件。测试因此调整为允许首次空批次，但要求下一轮必须领取剩余事件，并最终证明4条事件全部被领取且ID互不重复。数据库隔离级别和Outbox业务实现保持不变。

## 26. 阶段8.6：有限重试与最终死信闭环

状态：**已实现并通过全量88项测试，阶段8.6正式关闭。**

### 26.1 实施步骤

1. 定义消息投递次数、失败Header和可靠转发结果；
2. 实现带Publisher Confirm与Return的重试/死信转发器；
3. 改造消费者，将临时异常送入TTL重试队列、永久异常送入最终死信队列；
4. 重试耗尽时条件更新仍处于`PENDING/QUEUED`的对应任务为`FAILED`；
5. 验证次数、TTL回流、最终死信、转发失败保护和手工处理流程。

### 26.2 次数语义

`simulation.messaging.max-delivery-attempts=3`表示总处理次数包含首次消费：

```text
x-delivery-attempt=1：主队列首次消费
x-delivery-attempt=2：第一次TTL重试
x-delivery-attempt=3：第二次TTL重试
第3次仍出现临时异常：进入最终死信队列
```

Outbox首次发布的消息没有该Header，消费者按1处理。每次由应用发布到重试交换机时写入下一次次数。RabbitMQ自动生成的`x-death`仍用于底层追踪，但不作为本项目业务重试计数，避免依赖复杂且会随拓扑变化的Broker内部结构。

### 26.3 失败追踪Header

重试或死信消息保留原始body、`messageId`、`type`、优先级和业务Header，并增加：

| Header | 含义 |
|---|---|
| `x-delivery-attempt` | 下一次/最终处理次数 |
| `x-last-error` | 截断后的最后异常摘要 |
| `x-last-failed-at` | 本次失败的UTC时间 |
| `x-final-failure` | 进入最终死信时为`true` |

### 26.4 可靠转发顺序

消费者不能先ACK再转发：

```text
发布到重试或死信交换机
  -> 等待Publisher Confirm
  -> 检查mandatory Return
  -> Confirm ACK且无Return
  -> ACK原消息
```

如果转发发生NACK、Return、超时或连接异常，原消息执行`basicNack(requeue=true)`，使RabbitMQ继续保留原消息。转发成功但ACK前宕机可能产生重复重试/死信消息，仍由业务幂等吸收；系统保证At-Least-Once，不宣称跨Broker操作Exactly Once。

### 26.5 异常分类

| 场景 | 处理 |
|---|---|
| JSON非法、版本不支持、字段/Header不一致 | 可靠发布到最终死信后ACK原消息 |
| 任务不存在、未来轮次 | 可靠发布到最终死信后ACK原消息 |
| 数据库连接、网络或其他未预期运行时异常，次数未耗尽 | 发布到TTL重试队列后ACK原消息 |
| 临时异常且次数已耗尽 | 发布到最终死信；条件标记待执行任务失败；ACK原消息 |
| Worker已可靠保存业务`FAILED` | 直接ACK，不做消息重试 |
| 重复、旧轮次、取消或已终态 | 直接ACK |

### 26.6 重试耗尽后的数据库状态

死信发布成功后，仅当任务仍满足以下条件时更新为`FAILED`：

```text
id = taskId
AND status IN ('PENDING', 'QUEUED')
AND retry_count = attemptNo - 1
```

错误信息记录“消息处理重试耗尽”和最后异常摘要。已经`SUCCEEDED/FAILED/CANCELLED`的任务不覆盖；`RUNNING`任务交给现有心跳超时恢复器判断，避免错误终止仍在运行的Worker。

### 26.7 死信查看与人工处理边界

本阶段不新增死信数据库表和管理API。最终死信保存在`simulation.task.dead.queue`，可通过RabbitMQ Management UI或`rabbitmqctl`查看数量，并通过管理界面获取消息、检查失败Header后人工重新发布到主交换机。自动重放被禁止，避免永久错误形成循环；人工重放前必须先修复根因并核对任务当前状态与`attemptNo`。

### 26.8 验收标准

- 临时异常不会立即无限重入主队列，而是经过10秒TTL延迟；
- 总处理次数最多3次，第3次失败进入最终死信；
- 永久异常不经过重试队列；
- 只有Confirm ACK且无Return才ACK原消息，转发失败保留原消息；
- 最终死信携带次数、最后错误和失败时间；
- 重试耗尽只条件更新匹配轮次的待执行任务，不覆盖终态或RUNNING；
- 全量回归通过，队列和数据库无测试残留。

## 15. 阶段8.2实现与验收记录

- RabbitMQ容器：`rabbitmq:4.3.2-management`，虚拟主机`wireless_sim`；
- 主拓扑：`simulation.task.exchange`通过`simulation.task.execute`绑定`simulation.task.execute.queue`；
- 重试拓扑：`simulation.task.retry.exchange`通过`simulation.task.retry`绑定`simulation.task.retry.queue`；
- 最终死信拓扑：`simulation.task.dlx`通过`simulation.task.dead`绑定`simulation.task.dead.queue`；
- 主队列参数：`x-max-priority=5`；
- 重试队列参数：`x-message-ttl=10000`，到期后回到主交换机的执行路由；
- 三个交换机和三个队列均为持久化资源，本地队列类型为Classic Queue；
- 拓扑只在`dispatch-mode=rabbitmq`时由Spring RabbitAdmin声明；
- `RabbitTopologyIT`连接真实Broker验证资源存在、队列参数和绑定一致；
- Broker端`rabbitmqctl`核验结果与代码声明一致；
- 全量28个测试通过，阶段8.2完成。

本记录不表示Outbox、生产者、消费者和ACK闭环已经完成；这些内容从阶段8.3开始逐步实现。

## 16. 阶段8.3第1步：数据库迁移记录

- 已新增`V3__create_outbox_event.sql`并由Flyway在真实MySQL 8.4执行成功；
- 历史迁移`V1/V2`保持不变，数据库当前版本为v3；
- 字段、默认值、两个唯一约束和两个发布相关组合索引与本设计一致；
- `aggregate_id`保持通用聚合引用，不添加只能指向单一业务表的外键；
- `DatabaseMigrationIT`验证核心表、索引和关键默认值；
- 定向3项测试和全量30项测试通过。

本小步尚未新增Java领域对象或Mapper，也没有修改任务提交与人工重试事务。

## 17. 阶段8.3第2步：Java持久化层记录

- 已新增`OutboxStatus`，当前状态为`PENDING/SENDING/PUBLISHED`；
- 已新增`OutboxEvent`，完整映射V3表字段；
- 已新增`OutboxEventMapper`及XML，支持`insertPending`、按事件ID查询和按业务唯一键查询；
- 新记录的状态、发布次数及首次可发送时间统一使用数据库默认值；
- JSON通过MySQL `CAST(... AS JSON)`校验后保存；
- MyBatis通过`useGeneratedKeys`把自增主键回填到Java对象；
- `OutboxEventMapperIT`验证完整映射和业务唯一约束；
- 定向2项测试与全量32项测试通过。

本小步仍未修改`TaskService`。只有下一步把`taskMapper`和`outboxEventMapper`放入同一个`@Transactional`方法，才能真正形成任务与事件的原子提交。

## 18. 阶段8.3第3步：业务成功路径接入记录

- 已新增`TaskExecutionRequestedMessage`，字段为`eventId/taskId/attemptNo/eventType/schemaVersion/occurredAt`；
- 已新增`TaskOutboxEventFactory`，统一事件常量、UUID、UTC时间、JSON序列化和优先级映射；
- `TaskService.submit`在任务INSERT后、事务提交前插入尝试1事件；
- `TaskService.retry`在状态更新后重新读取任务，并插入`retry_count + 1`对应事件；
- 幂等重放不创建第二条事件；
- 任务和事件操作都使用调用`submit/retry`时开启的同一个Spring事务；
- 业务优先级1—10映射到RabbitMQ优先级1—5；
- 任务API定向4项和全量32项测试通过。

当前已经验证正常成功路径。下一步仍需故障注入，让Outbox插入主动抛出异常，并在事务结束后查询数据库，分别证明“新任务不存在”和“重试状态、次数及版本没有变化”。

## 19. 阶段8.3第4步：原子回滚验收记录

- 新增`TaskOutboxTransactionIT`；
- 仅将`OutboxEventMapper`替换为抛出运行时异常的`@MockitoBean`；
- 真实任务INSERT后Outbox失败，事务结束后任务和事件都不存在；
- 真实重试UPDATE后Outbox失败，事务结束后任务状态、重试次数、版本和错误信息全部保持原值；
- 测试方法不使用外层测试事务，确保观察的是服务事务结束后的数据库最终状态；
- 定向2项测试和全量34项测试通过。

阶段8.3验收结论：任务首次提交或人工重试只会出现两种结果——任务变化和待发布事件一起提交，或者两者一起回滚。不存在由当前业务入口产生的“任务已进入待执行状态但没有可恢复事件”状态。下一阶段8.4将读取这些`PENDING`事件并可靠发布到RabbitMQ。

## 20. 阶段8.4第1步：批量领取与租约恢复记录

- 新增`OutboxPublisherProperties`，类型安全绑定`enabled/scanInterval/batchSize/leaseDuration/confirmTimeout/retryBaseDelay/retryMaxDelay`；
- 默认配置为每秒扫描、每批20条、租约2分钟、Confirm超时5秒、发布重试5秒起步且最多退避到5分钟；
- 配置启动时校验：批量为1—1000、所有时长大于0、最大重试延迟不小于基础延迟、租约不短于单批逐条等待Confirm的最坏时间；
- `OutboxClaimService.claimBatch`在一个短事务中完成“加锁查询、标记SENDING、回查最新记录”；
- 加锁SQL使用`FOR UPDATE SKIP LOCKED`，多个发布器跳过其他事务已经锁定的行，而不是等待或重复领取；
- 领取后写入`claimed_by/claimed_at`并将`publish_attempts + 1`；更新数量或回查数量不一致时抛出运行时异常，使整个领取事务回滚；
- 查询按`next_attempt_at, created_at, id`排序，与`idx_outbox_publish_scan(status,next_attempt_at,created_at,id)`索引顺序一致，优先发送最早到期事件并减少额外排序与锁扫描；
- `recoverExpiredClaims`把租约外的`SENDING`恢复为`PENDING`，清空领取信息并留下可观察的错误摘要；
- 新增真实MySQL集成测试，验证到期筛选、并发无重复领取、超时恢复；定向3项和全量37项测试通过。

本步骤的事务结束后才允许访问RabbitMQ，避免等待网络确认时占用数据库行锁。当前尚未实现`RabbitTemplate`发送、Publisher Confirm、Return或发布结果状态更新，这些属于8.4后续步骤。

## 21. 阶段8.4第2步：单消息Confirm与Return记录

- 新增`OutboxPublishOutcome`，区分`ACK/RETURNED/NACK/TIMEOUT/SEND_FAILED`；
- 新增`OutboxPublishResult`，统一返回结果类型和可持久化的简短诊断信息；
- 新增`OutboxMessagePublisher`，只在`simulation.execution.dispatch-mode=rabbitmq`时注册；
- 直接使用Outbox中的JSON载荷作为UTF-8消息正文，避免再次序列化改变消息内容；
- 消息设置`deliveryMode=PERSISTENT`、JSON Content-Type、`messageId=eventId`、`correlationId=eventId`、事件类型、优先级和UTC发生时间；
- Header包含`eventId/outboxId/aggregateType/aggregateId/attemptNo/schemaVersion`，供消费者校验、追踪和幂等处理；
- 每条消息使用事件ID创建`CorrelationData`，等待时间由`confirm-timeout=5s`控制；
- Confirm完成后先检查`CorrelationData.returned`：存在Return时，即使Confirm ACK也判定为`RETURNED`；
- 无Return且Confirm ACK才返回成功；Confirm NACK、超时、异步异常和同步AMQP异常分别返回结构化失败；
- 单元测试覆盖5种结果，真实RabbitMQ集成测试验证持久化JSON消息进入主执行队列；全量43项测试通过。

本步仍不修改`outbox_event`。第3步将在独立事务中校验`id/status/claimed_by`后，把ACK更新为`PUBLISHED`，把其他结果恢复为`PENDING`并计算下一次重试时间。Publisher Confirm只表示Broker接管消息，不能表示消费者已经处理。

## 22. 阶段8.4第3步：结果落库与指数退避记录

- 新增`markPublished`和`rescheduleAfterFailure`，都要求`id/status=SENDING/claimed_by`同时匹配；
- ACK且无Return时更新`PUBLISHED/published_at`，清空领取和错误信息；
- 其他结果恢复`PENDING`，清空领取信息，写入最多1000字符的错误摘要；
- 退避规则为5、10、20、40、80、160、300秒，后续保持300秒；
- `next_attempt_at`使用MySQL `CURRENT_TIMESTAMP(3)`计算，避免应用与数据库时钟偏差；
- `OutboxPublishResultService`使用独立短事务，不把RabbitMQ网络等待放入数据库事务；
- 错误发布器或迟到结果更新0行，不覆盖租约恢复后新发布器的状态；
- 退避单元测试和真实MySQL成功、失败、所有权测试通过。

## 23. 阶段8.4第4步：定时批量发布闭环记录

- 新增`OutboxPublisherScheduler`，同时要求`dispatch-mode=rabbitmq`和`simulation.outbox.enabled=true`；
- 每个应用实例生成生命周期内固定且全局唯一的`publisherId`；
- `publishOnce`按fixedDelay运行，每轮领取最多20条并逐条发送、逐条记录结果；
- `recoverExpiredOnce`独立恢复超过2分钟租约的`SENDING`事件；
- fixedDelay从上一轮结束后计时，上一轮因Confirm较慢时不会重叠堆积；
- 一条消息发送或结果落库异常不会阻断同批其他事件；
- 结果落库异常时不猜测Broker结果，事件保持`SENDING`等待租约恢复；
- 真实MySQL与RabbitMQ完整测试证明`PENDING -> SENDING -> PUBLISHED`和消息入队形成闭环；
- MySQL JSON类型会规范化空格和键顺序，因此测试使用JSON树做语义比较，不能依赖原始字符串顺序。

## 24. 阶段8.4第5步：故障验收与关闭记录

| 故障或竞争场景 | 已验证行为 |
|---|---|
| 两个发布器并行领取 | `FOR UPDATE SKIP LOCKED`使事件集合不重复 |
| 错误或旧`claimed_by`回写 | 条件UPDATE影响0行，不覆盖当前状态 |
| Confirm ACK且正常路由 | 消息进入主队列，Outbox标记`PUBLISHED` |
| Confirm ACK但路由键不存在 | mandatory Return，结果为`RETURNED`，不能标记成功 |
| Broker NACK | 结果为`NACK`，安排发布重试 |
| Confirm超时 | 结果未知，保留事件并指数退避，允许未来重复发送 |
| Broker连接/发送异常 | 结果为`SEND_FAILED`并进入退避 |
| 消息已发送但结果落库失败 | 保持`SENDING`，租约超时后恢复`PENDING`重发 |
| 发布器领取后宕机 | 2分钟租约恢复，不永久卡死 |

阶段8.4最终全量55项测试通过，0失败、0错误、0跳过。生产者侧已达到“允许重复、不静默丢失、状态可恢复”的验收目标。消费者尚未实现，消息是否被业务成功处理仍不在本阶段保证范围；下一阶段8.5实现手动ACK和消费幂等。
