# Java/Python Worker通信契约

## 总链路

```text
任务提交 -> MySQL任务+Outbox -> RabbitMQ主队列
       -> Python领取Java任务 -> GRPO推理
       -> Java成功/失败回调 -> MySQL终态 -> ACK
```

Java启动Python模式时设置：

```powershell
$env:SIMULATION_DISPATCH_MODE="rabbitmq"
$env:SIMULATION_WORKER_MODE="python"
$env:SIMULATION_WORKER_TOKEN="请替换为随机长令牌"
```

`worker-mode=python`会关闭Java Mock消费者，避免Java和Python竞争同一主队列；Outbox发布器和超时恢复器继续工作。

## 内部认证

三个接口均要求请求头：

```http
X-Worker-Token: <SIMULATION_WORKER_TOKEN>
```

令牌未配置返回503，错误令牌返回401。该令牌只用于服务间认证，不是用户JWT，也不能提交Git。

## 1. 领取任务

`POST /api/v1/internal/worker/tasks/{taskId}/attempts/{attemptNo}/claim`

```json
{"workerId":"python-laptop"}
```

主要结论：`CLAIMED`表示首次领取；`RESUMABLE`表示同轮次已是RUNNING，消息可重投并重新闭环；`ALREADY_HANDLED`和`STALE_ATTEMPT`可直接ACK；`REJECTED`进入死信。

成功领取响应含`executionId`、`modelId`、场景和评估配置。单位字段直接写进名称，例如`wptTransmitPowerWatt`，避免Java与Python隐式换算。

## 2. 成功回调

`POST /api/v1/internal/worker/tasks/{taskId}/attempts/{attemptNo}/complete`

```json
{
  "executionId": 12,
  "modelId": "grpo-rsma-throughput-v1",
  "checkpointSha256": "64位十六进制摘要",
  "baseSeed": 2026,
  "throughputMean": 422.0089,
  "throughputStd": 0.67,
  "throughputMin": 421.3,
  "throughputMax": 422.7,
  "totalTimesteps": 1000,
  "totalEvaluationTimeSeconds": 2.5,
  "artifactPath": "python-worker/artifacts/task-1/attempt-1"
}
```

Java在同一事务内执行`task -> SUCCEEDED`、`task_execution -> SUCCEEDED`和`simulation_result`插入。AoI不在请求中，Java明确写NULL。重复完成返回`ALREADY_HANDLED`，不会插入第二份结果。

## 3. 失败回调

`POST /api/v1/internal/worker/tasks/{taskId}/attempts/{attemptNo}/fail`

```json
{"executionId":12,"errorCode":"MODEL_WEIGHT_LOAD_FAILED","errorMessage":"..."}
```

错误码包括`MODEL_OR_SCENE_INCOMPATIBLE`、`MODEL_WEIGHT_NOT_FOUND`、`MODEL_WEIGHT_LOAD_FAILED`和`INFERENCE_FAILED`。

## ACK、重试与死信

- Java成功保存终态或确认任务已处理后，Python才ACK；
- Java网络错误/5xx属于临时错误，消息进入阶段8已有的TTL重试队列；
- 非法消息、模型边界不兼容和Java 4xx属于永久错误，进入最终死信；
- 重试或死信发布使用持久化消息、mandatory和Publisher Confirm，确认成功后才ACK原消息；
- 投递默认最多处理3次，不做无限循环。

该语义是“At-Least-Once投递 + 数据库幂等业务效果”，不是消息传输Exactly Once。
