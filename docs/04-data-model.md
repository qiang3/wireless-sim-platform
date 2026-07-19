# 数据模型

## 1. 设计目标

MVP围绕“配置场景—提交实验—可靠分发—异步执行—查看结果”建立六个核心实体。业务状态保存在MySQL；阶段7先由Java模拟Worker形成闭环，阶段8再由Transactional Outbox与RabbitMQ承担可靠任务分发，Redis只保存适合短期读取的数据，避免把缓存当作事实来源。

## 2. 核心实体

| 实体 | 职责 | 关键字段 |
|---|---|---|
| `app_user` | 平台用户及权限 | 用户名、密码摘要、角色、状态 |
| `simulation_scenario` | 无线通信仿真场景及参数模板 | 场景名称、优化目标、参数JSON、版本 |
| `experiment_task` | 一次可追踪的实验任务 | 任务编号、场景快照、算法、状态、进度、重试次数、幂等键、请求摘要、锁版本 |
| `task_execution` | 每一次真实执行尝试 | 尝试序号、执行器、心跳、开始与结束时间 |
| `simulation_result` | 实验指标和结果文件索引 | 吞吐量、AoI、收敛步数、扩展指标JSON、文件路径 |
| `outbox_event` | 与任务事务同时落库的待发布事件 | 事件ID、任务ID、尝试号、JSON载荷、发布状态、领取租约、重试时间 |

## 3. 主要关系

- 一个用户可以创建多个场景和实验任务。
- 一个场景可以产生多个实验任务。
- 一个实验任务允许因失败而产生多次执行记录。
- 一个成功任务对应一份汇总结果，明细曲线以文件形式保存。
- 一个任务可以因首次提交和人工重试产生多个Outbox事件，但同一任务的同一次尝试只能产生一个执行请求事件。

## 4. 状态与一致性

任务状态为：

`PENDING -> QUEUED -> RUNNING -> SUCCEEDED / FAILED / CANCELLED`

- 数据库中的任务状态是最终事实来源。
- 场景使用`version`、任务使用`lock_version`进行乐观锁校验，避免并发更新覆盖。
- 数据库通过`(creator_id, idempotency_key)`联合唯一约束防止重复点击或网络重试创建重复任务。
- `V2`已增加`scenario_snapshot_json`和`request_hash`：前者保证任务输入可复现，后者识别相同幂等键是否对应相同请求。
- 阶段8接入RabbitMQ后，消息携带事件ID、任务ID、执行尝试号、事件类型和消息版本，执行器再从数据库读取完整参数；尝试号用于阻止旧消息错误抢占后续重试。
- `V3`创建`outbox_event`；`(aggregate_type, aggregate_id, event_type, attempt_no)`唯一约束防止同一次业务尝试产生重复事件。
- `(status, next_attempt_at, created_at, id)`索引服务于发布器候选扫描，`(status, claimed_at)`索引服务于恢复领取后超时的`SENDING`事件。
- Outbox发布状态机为`PENDING -> SENDING -> PUBLISHED`；发布失败或租约超时走`SENDING -> PENDING`，等待下一次领取。
- 领取时写入`claimed_by/claimed_at`并递增`publish_attempts`；成功或失败落库必须同时匹配主键、`SENDING`状态和领取者。
- 发布失败按`5 * 2^(publish_attempts-1)`秒计算下一次时间，并以300秒封顶；`next_attempt_at`由MySQL当前时间计算。
- ACK且没有Return时记录`published_at`；NACK、Return、Confirm超时和发送异常保留错误摘要并重新调度。
- 执行器定期更新`heartbeat_at`，后续由补偿任务识别失联任务。
