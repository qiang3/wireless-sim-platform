# 阶段9验收记录

## 已完成能力

- 独立GRPO评估入口支持场景参数、确定性seed、可信权重摘要和标准JSON；
- Java提供领取、成功、失败三个Worker接口，并使用独立静态令牌认证；
- Python直接消费RabbitMQ任务，调用PyTorch模型并通过HTTP回调Java；
- Java Mock与Python消费者通过`simulation.execution.worker-mode`互斥；
- 任务、执行记录和推理结果由Java在MySQL事务中形成一致终态；
- 重复领取返回相同执行记录，重复成功回调不会生成第二份结果；
- AoI全链路保持空值，模型来源标记为`PRETRAINED_MODEL`且`trainingPerformed=false`；
- Java不可用时有限重试，非法消息和永久错误进入死信。

## 自动化验收范围

- Java全量97项测试通过，0失败、0错误、0跳过，覆盖原阶段1—8功能和新增Worker API；
- `PythonWorkerApiIT`使用真实MySQL验证Worker Token、领取、重复领取、成功回调和重复回调；
- Python 10项测试通过，覆盖消息版本、单位映射、AoI不得伪造、成功后ACK、Java网络异常转入重试及心跳配置校验；
- 使用本地真实GRPO权重在CUDA上连续运行两次，相同seed得到完全一致的吞吐量业务指标。

本次真实复现测试的两次结果均为：吞吐量均值`39.378569`、标准差`1.207378`、最小值`38.171191`、最大值`40.585947`。耗时不要求一致，因为它不是科研业务结果。

## 真实全链路手工验收

2026-07-21使用本地真实MySQL、RabbitMQ、Redis、Java服务、Conda Python Worker和GRPO权重完成手工验收，观察结果与设计一致：

1. 提交后`experiment_task=PENDING`、`outbox_event=PENDING`，尚无执行记录和结果；
2. Outbox发布后事件为`PUBLISHED`，RabbitMQ主队列出现一条Ready消息；
3. Python领取后任务为`RUNNING`，形成唯一`task_execution=RUNNING`，消息处于Unacked；
4. GRPO推理及Java成功回调后任务为`SUCCEEDED/100%`，执行记录为`SUCCEEDED`；
5. `simulation_result`只有一条，吞吐量有值、AoI和收敛步为空，`metrics_json`包含模型ID、权重SHA-256、seed和推理统计；
6. 消息ACK后RabbitMQ主队列`Ready=0、Unacked=0`。

结果查询接口同时返回通用结果列和完整`metrics`对象。JAVA_MOCK保留旧版`deterministicSeed/simulationMode/scientificResult`兼容字段；GRPO不存在这些字段时返回`null`，不再伪造默认值。

调试中还复现了“断点暂停超过Pika心跳窗口，Broker先关闭连接，finally再次close触发`ConnectionWrongStateError`”。最终修复为：Python心跳通过`RABBITMQ_HEARTBEAT_SECONDS`覆盖，连接关闭前检查`is_open`；Java通过`RABBITMQ_HEARTBEAT`覆盖。正常运行保持30秒，长断点调试可临时设为300秒。

## 本地运行步骤

1. `docker compose up -d mysql rabbitmq redis`；
2. Java设置`SIMULATION_WORKER_MODE=python`和`SIMULATION_WORKER_TOKEN`后启动；
3. Python设置相同Token、`GRPO_CHECKPOINT_PATH`后运行`python python-worker\worker\main.py`；
4. 从用户API提交3设备、RSMA、THROUGHPUT、GRPO任务；
5. 查询任务详情和结果，确认`SUCCEEDED/100%`、吞吐量有值、AoI为空。

长时间断点调试时可额外设置：

```powershell
$env:RABBITMQ_HEARTBEAT="300s"          # Java客户端
$env:RABBITMQ_HEARTBEAT_SECONDS="300"  # Python客户端
$env:JWT_ACCESS_TOKEN_TTL="60m"
```

## 仍然存在的边界

- 第一版只有单个预训练模型版本和3设备输入输出维度；
- 不支持在线训练、AoI、逐回合进度或推理中途取消；
- 本地模型产物路径仅适用于单机演示，多机部署应改为对象存储URI；
- 静态Token适合当前内部演示，生产环境可升级为mTLS或短期服务凭证；
- 超时恢复会把崩溃的RUNNING任务标记失败，之后仍由用户/平台重试生成新轮次。
