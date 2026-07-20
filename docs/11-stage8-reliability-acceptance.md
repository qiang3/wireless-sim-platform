# 阶段8可靠异步化验收记录

## 1. 验收结论

阶段8已经达到以下项目口径：

- RabbitMQ采用At-Least-Once投递，不宣称端到端Exactly Once；
- 任务状态、执行记录和结果由MySQL作为最终事实来源；
- Transactional Outbox避免“任务已提交但执行消息永久丢失”；
- 重复、旧轮次和ACK异常后的重新投递由数据库业务幂等吸收；
- 临时消息异常有限重试，永久异常或重试耗尽进入死信；
- Redis只保存可重建缓存和短期计数，不参与任务正确性；
- 缓存、Redis或RabbitMQ发生预期故障时均有明确恢复路径。

2026-07-20执行全量测试：`95`项，`0`失败，`0`错误，`0`跳过。

## 2. 故障与一致性验收矩阵

| 场景 | 保护机制 | 自动化或实测证据 | 预期结果 |
|---|---|---|---|
| 任务写入后Outbox插入失败 | 同一Spring/MySQL事务 | `TaskOutboxTransactionIT` | 任务与事件同时回滚 |
| Outbox发送网络异常、NACK、超时、Return | Confirm、Return、失败落库、指数退避 | Outbox发布器单元/集成测试 | 事件回到`PENDING`，等待再次发送 |
| 发布成功但结果状态未落库 | `SENDING`租约恢复 | Outbox故障集成测试 | 超时后恢复为`PENDING`并允许重复发送 |
| 同一消息重复到达 | 状态条件更新、`attemptNo`、执行记录唯一约束 | `RabbitTaskConsumerIT` | 只有一条执行记录和一份结果 |
| 旧轮次或未来轮次消息 | `retry_count + 1`与`attemptNo`校验 | 消息准备服务测试 | 旧消息ACK吸收，未来消息拒绝 |
| 消费者临时异常 | 10秒TTL重试、最多3次 | `RabbitTaskRetryFlowIT` | 延迟重试，不形成高速重试风暴 |
| 永久非法消息或重试耗尽 | 最终死信队列 | Listener与Rabbit重试测试 | 消息可人工定位，不无限回流 |
| ACK前应用退出 | Broker重新投递 + 业务幂等 | 重复投递测试覆盖等价窗口 | 允许重复消息，不允许重复业务结果 |
| Redis连接失败 | 缓存回源、限流Fail Open | `RedisFailOpenIT` | 核心查询和提交继续可用 |
| Redis缓存JSON损坏 | 删除脏键、回源MySQL、重建 | `TaskRedisFlowIT` | 返回MySQL真值并恢复缓存 |
| MySQL事务回滚 | `afterCommit`才删除缓存 | `TaskOutboxTransactionIT` | 回滚后原缓存不被误删 |
| RabbitMQ容器重启 | durable交换机/队列/绑定和命名卷 | 真实重启 + 连接/拓扑测试 | 3个队列恢复且应用可重新连接 |
| Redis容器重启 | 无持久化、数据可重建 | 写临时键后真实重启 | 临时键消失，连接恢复，MySQL数据不受影响 |

## 3. 完整链路一致性

`StageEightEndToEndIT`使用真实MySQL、Redis和RabbitMQ，执行以下链路：

```text
TaskService.submit
  -> experiment_task(PENDING) + outbox_event(PENDING) 同事务提交
  -> OutboxPublisherScheduler领取并可靠发布
  -> outbox_event(PUBLISHED)
  -> RabbitMQ消费者手动ACK
  -> task_execution(attemptNo=1, SUCCEEDED, heartbeat完整)
  -> experiment_task(SUCCEEDED, progress=100)
  -> simulation_result恰好一条
  -> PENDING旧缓存删除，下一次读取回源并缓存SUCCEEDED
```

测试同时断言吞吐量、AoI、收敛步数和`metrics_json`均已生成，但这些结果标记为`JAVA_MOCK`，不能作为GRPO/PPO科研结论。

## 4. 本地复现实验

先确保MySQL、RabbitMQ和Redis均为`healthy`，再执行：

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:Path="$env:JAVA_HOME\bin;D:\MyProgramFiles\apache-maven-3.9.6\bin;$env:Path"
mvn -s .mvn\settings.xml clean test
```

单独复现阶段8端到端链路：

```powershell
mvn -s .mvn\settings.xml clean -Dtest=StageEightEndToEndIT test
```

重启演练：

```powershell
docker compose restart redis rabbitmq
docker compose ps
mvn -s .mvn\settings.xml clean "-Dtest=RedisConnectionIT,RabbitConnectionIT,RabbitTopologyIT" test
```

检查RabbitMQ队列：

```powershell
docker exec wireless-sim-rabbitmq rabbitmqctl -p wireless_sim list_queues name durable messages_ready messages_unacknowledged
```

## 5. 验收后的边界

- 当前Worker仍为Java固定种子合成模拟器，真实Python/PyTorch科研仿真属于阶段9；
- Redis限流为单用户固定窗口，存在窗口边界突发，不是精确滑动窗口；
- Redis采用Fail Open，故障期间暂时失去流量保护，生产环境需要告警与外围限流；
- 死信当前由RabbitMQ管理界面和命令行人工处理，尚未提供管理后台；
- 阶段8重点证明消息与缓存基础设施的可靠性，不等同于跨地域容灾或生产级SLA。
