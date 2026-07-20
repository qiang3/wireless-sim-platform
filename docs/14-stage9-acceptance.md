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
- Python 8项测试通过，覆盖消息版本、单位映射、AoI不得伪造、成功后ACK和Java网络异常转入重试；
- 使用本地真实GRPO权重在CUDA上连续运行两次，相同seed得到完全一致的吞吐量业务指标。

本次真实复现测试的两次结果均为：吞吐量均值`39.378569`、标准差`1.207378`、最小值`38.171191`、最大值`40.585947`。耗时不要求一致，因为它不是科研业务结果。

## 本地运行步骤

1. `docker compose up -d mysql rabbitmq redis`；
2. Java设置`SIMULATION_WORKER_MODE=python`和`SIMULATION_WORKER_TOKEN`后启动；
3. Python设置相同Token、`GRPO_CHECKPOINT_PATH`后运行`python python-worker\worker\main.py`；
4. 从用户API提交3设备、RSMA、THROUGHPUT、GRPO任务；
5. 查询任务详情和结果，确认`SUCCEEDED/100%`、吞吐量有值、AoI为空。

## 仍然存在的边界

- 第一版只有单个预训练模型版本和3设备输入输出维度；
- 不支持在线训练、AoI、逐回合进度或推理中途取消；
- 本地模型产物路径仅适用于单机演示，多机部署应改为对象存储URI；
- 静态Token适合当前内部演示，生产环境可升级为mTLS或短期服务凭证；
- 超时恢复会把崩溃的RUNNING任务标记失败，之后仍由用户/平台重试生成新轮次。
