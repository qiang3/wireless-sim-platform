# 阶段7：Java模拟执行与结果闭环设计

状态：**已完成并通过全量测试验收**。

## 1. 阶段目标

使用MySQL轮询和本地Java线程池打通“任务提交—排队—执行—进度—取消—结果—失败—重试—超时恢复”闭环。本阶段不引入RabbitMQ，不运行真实GRPO/PPO训练；消息可靠性在阶段8实现，Python/PyTorch真实仿真在阶段9实现。

## 2. 调度与线程池

- 调度器每1秒扫描一次，每批最多处理20个任务；
- `PENDING`任务通过带状态条件的原子更新进入`QUEUED`；
- 调度器只分发尚无当前执行尝试记录的`QUEUED`任务；
- 使用`ThreadPoolTaskExecutor`，固定2个工作线程，内存等待队列容量20；
- 线程数、队列容量、批次和扫描间隔全部配置化；
- 为同一任务和尝试号创建`task_execution`时，依赖`(task_id, attempt_no)`唯一约束防止并发重复执行；
- 线程池拒绝任务时记录告警、释放单实例占用标记并保留`QUEUED`，等待下一轮扫描，不静默丢失。

## 3. 状态与进度

```text
PENDING -> QUEUED -> RUNNING -> SUCCEEDED
   |          |          |-> FAILED -> QUEUED
   |          |          |-> CANCELLED
   |          |-> FAILED / CANCELLED
   |-> CANCELLED
```

- 状态变化增加`lock_version`；
- 进度与心跳更新不增加版本，避免客户端取消请求因频繁进度更新而持续过期；
- Worker把模拟拆成10个步骤，进度按10%递增；
- 进度更新SQL要求任务仍为`RUNNING`；
- 每次进度更新同时刷新执行记录`heartbeat_at`。

## 4. 协作式取消

- 用户取消后，任务状态变为`CANCELLED`；
- Worker每个模拟步骤查询一次当前任务状态；
- 发现取消后停止后续计算，不保存结果，并把当前执行记录标记为`CANCELLED`；
- 成功与取消都通过带当前状态条件的更新竞争，只有一方能够成功；
- 结果插入、执行记录成功和任务成功在同一事务中提交，避免部分成功。

## 5. 执行记录

- 一条`experiment_task`表示一次业务实验；
- 每次实际执行在`task_execution`中新增一条记录；
- `attempt_no = retry_count + 1`，初次执行为1，最多重试2次时总共最多执行3次；
- 执行状态为`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`；
- `worker_id`记录本地实例标识和线程名称；
- 保存开始时间、结束时间、心跳和截断后的错误信息。

## 6. JAVA_MOCK模拟引擎

Java模拟器只验证后端执行闭环，不伪装成真实强化学习训练：

- 使用场景与训练随机种子生成确定性波动，相同输入产生相同输出；
- 吞吐量由天线、绿色能量、WPT功率、设备功率、负载和接入方式的合成公式生成；
- AoI与设备数量、到达率正相关，与合成吞吐量负相关；
- 收敛步数在最大训练步数的65%—85%区间内确定性生成；
- 不人为设置GRPO优于PPO，算法只作为任务元数据；
- 指标明确保存`simulationMode=JAVA_MOCK`和`scientificResult=false`；
- 阶段7不生成训练曲线文件，`artifact_path`为空；
- 阶段9由Python/PyTorch Worker替换模拟引擎，产生真实算法结果。

## 7. 失败、重试与恢复

- Worker异常时，在事务中把任务和当前执行记录标记为`FAILED`，保存错误并且不生成结果；
- 用户通过现有重试接口执行`FAILED -> QUEUED`，增加`retry_count`并创建新的执行尝试；
- 阶段7只支持用户主动重试，不自动重试；
- 恢复调度器每10秒扫描一次；
- `RUNNING`执行记录的心跳超过30秒未更新时，任务和执行记录变为`FAILED`，错误为Worker心跳超时；
- 阶段8再通过RabbitMQ、Outbox、ACK、消费幂等和死信队列增强可靠性。

## 8. 计划配置

```yaml
simulation:
  execution:
    enabled: true
    core-pool-size: 2
    max-pool-size: 2
    queue-capacity: 20
    dispatch-interval-ms: 1000
    dispatch-batch-size: 20
    step-delay-ms: 200
    recovery-scan-interval-ms: 10000
    heartbeat-timeout-seconds: 30
```

自动化测试会关闭默认调度器或把等待时间设为0，保证测试确定且快速。

## 9. 分步实施

- [x] 第1步：记录确认后的阶段7设计并检查中断状态；
- [x] 第2步：增加执行配置、线程池和执行状态枚举；
- [x] 第3步：增加执行记录持久层，以及`PENDING -> QUEUED -> RUNNING`条件更新和原子抢占事务；
- [x] 第4步：实现MySQL轮询调度器和Java Worker骨架；
- [x] 第5步：实现`JAVA_MOCK`模拟、进度、心跳和协作取消；
- [x] 第6步：实现成功、失败、重试、超时恢复、结果查询及相关测试；
- [x] 第7步：完成全量自动化测试、文档验收和面试问答整理。

第3步验收结果：两个独立线程并发抢占同一`QUEUED`任务时，只有一个条件更新成功，最终只创建一条`attempt_no = 1`的`RUNNING`执行记录。任务状态变为`RUNNING`，`lock_version`经过入队和抢占两次状态变化后从0增加到2。定向集成测试1个，0失败、0错误、0跳过。

第4步验收结果：调度器能够扫描`PENDING`任务并条件入队，再将`QUEUED`任务提交到专用`simulation-worker-*`线程池。单实例通过`inFlightTaskIds`避免相邻扫描重复提交，多实例或并发Worker最终通过数据库条件更新完成互斥抢占。连续触发两次调度后只创建一条执行记录，`worker_id`包含本地实例标识和真实线程名。第3、4步定向集成测试共2个，0失败、0错误、0跳过。

第5步验收结果：`JavaMockSimulationEngine`根据场景种子和训练种子生成确定性合成结果，相同输入输出完全一致；结果明确标记`simulationMode=JAVA_MOCK`和`scientificResult=false`，算法类型不参与性能公式。Worker将执行拆为10步，每步按10%更新任务进度并在同一事务中刷新执行记录心跳，进度更新不增加`lock_version`。用户取消`RUNNING`任务后，Worker在步骤边界检测`CANCELLED`状态，停止后续计算并将当前执行记录标记为`CANCELLED`。第3至第5步定向测试共5个，0失败、0错误、0跳过。

第6步验收结果：正常执行在同一事务中完成结果插入、执行记录`SUCCEEDED`和任务`SUCCEEDED`；任一步失败均整体回滚。Worker异常将任务和当前执行记录同时标记为`FAILED`且不生成结果，修复输入后可通过原任务手动重试，保留第一次失败记录并以`attempt_no = 2`创建成功记录。恢复器能够二次校验并处理心跳超时执行。`GET /api/v1/tasks/{id}/result`通过任务所有权联合查询，非本人结果统一返回404。第3至第6步定向测试共11个，0失败、0错误、0跳过。

第7步验收结果：修正Maven Surefire默认不执行`*IT`的问题，将单元测试和数据库集成测试统一纳入`mvn clean test`。从空`target`目录重新编译63个主源码文件和12个测试源码文件，共执行26个测试，0失败、0错误、0跳过。测试数据清理后无测试用户、孤立执行记录或孤立结果。阶段7全部验收标准均已满足。

## 10. 验收标准

- PENDING任务能够自动完成到SUCCEEDED并保存结果；
- 进度、心跳和执行记录随Worker运行更新；
- 运行中取消后停止计算且不生成结果；
- 同一任务同一尝试不会重复执行；
- Worker异常产生FAILED状态且可以通过原任务重试；
- 失联任务可以被超时恢复器识别；
- 结果查询只能访问本人任务；
- JAVA_MOCK结果可复现且明确标记为非科研结果；
- 全量自动化测试通过后，阶段7才能标记完成并写入简历材料。
