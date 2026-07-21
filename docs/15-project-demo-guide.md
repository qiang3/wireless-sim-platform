# 项目演示与截图取证手册

## 1. 文档目标

本手册用于稳定复现并展示以下真实链路：

```text
ApiPost提交实验
→ Spring Boot事务写入任务与Outbox
→ Outbox可靠发布到RabbitMQ
→ Python Worker领取任务
→ PyTorch加载预训练GRPO权重并评估
→ Python回调Java
→ Java事务保存任务、执行记录与结果
→ RabbitMQ手动ACK
→ 用户查询吞吐量和模型元数据
```

演示分为两遍：

1. **稳定性预演**：不设置断点，确认整条链路可以一次成功；
2. **截图取证**：放慢Outbox并在Python领取后暂停，按时间点保存关键证据。

本次演示不证明在线训练、AoI计算、前端或分布式Exactly Once。准确口径是预训练GRPO推理、RabbitMQ至少一次投递和数据库业务幂等。

## 2. 演示产物

截图统一保存到`docs/images/`：

```text
01-infrastructure.png
02-task-outbox-pending.png
03-rabbitmq-ready.png
04-task-running.png
05-rabbitmq-unacked.png
06-grpo-inference.png
07-database-result.png
08-result-api.png
09-java-tests.png
10-python-tests.png
```

其中README优先展示`03/06/07/08`四张，其余图片用于演示手册和面试证据。

## 3. 演示前检查

### 3.1 代码与权重

从项目根目录执行：

```powershell
git status
Test-Path "python-worker\pt\GRPO.pt"
```

预期：

- 明确当前Git改动，不在演示中临时修改业务代码；
- 权重检查返回`True`；
- `GRPO.pt`已被`.gitignore`忽略，不推送到远程仓库。

### 3.2 启动基础设施

```powershell
docker compose up -d mysql rabbitmq redis
docker compose ps
```

等待三个容器均健康：

```text
wireless-sim-mysql       healthy
wireless-sim-rabbitmq    healthy
wireless-sim-redis       healthy
```

此处保存`docs/images/01-infrastructure.png`。截图只需要容器名称、状态、端口，不要包含其他无关容器。

### 3.3 检查RabbitMQ队列

管理页面：

```text
http://localhost:15672
```

本地账号：

```text
username: wireless
password: wireless_dev
vhost: wireless_sim
```

也可以使用命令查看：

```powershell
docker compose exec -T rabbitmq rabbitmqctl -p wireless_sim list_queues name messages_ready messages_unacknowledged
```

演示开始前，下面三个队列应当没有遗留消息：

```text
simulation.task.execute.queue
simulation.task.retry.queue
simulation.task.dead.queue
```

如果队列有旧消息，先判断其对应任务状态和来源。不要为了截图直接清空未知消息。

## 4. ApiPost环境变量

建议创建本地演示环境：

| 变量 | 初始值 |
|---|---|
| `baseUrl` | `http://localhost:8080` |
| `token` | 留空，由登录脚本写入 |
| `scenarioId` | 留空，由创建场景脚本写入 |
| `taskId` | 留空，由提交任务脚本写入 |

所有受保护请求设置：

```http
Authorization: Bearer {{token}}
```

截图时隐藏完整JWT。JWT可以由登录后的后置脚本保存，但不应出现在README图片中。

## 5. 第一遍：无断点稳定性预演

### 5.1 启动Java

在第一个PowerShell窗口设置：

```powershell
$env:SIMULATION_DISPATCH_MODE="rabbitmq"
$env:SIMULATION_WORKER_MODE="python"
$env:SIMULATION_WORKER_TOKEN="wireless-worker-local-secret"
mvn -s .mvn/settings.xml spring-boot:run
```

确认：

```text
Started WirelessSimApplication
```

### 5.2 启动Python Worker

在第二个PowerShell窗口，从项目根目录执行：

```powershell
conda activate pytorch
$env:SIMULATION_WORKER_TOKEN="wireless-worker-local-secret"
$env:GRPO_CHECKPOINT_PATH=(Resolve-Path "python-worker\pt\GRPO.pt").Path
python python-worker\worker\main.py
```

确认控制台出现Worker ID、队列名称和checkpoint路径。

### 5.3 登录

如果用户已经存在，直接登录：

```http
POST {{baseUrl}}/api/v1/auth/login
Content-Type: application/json
```

```json
{
  "username": "demo_user_2026",
  "password": "DemoPass2026"
}
```

首次演示没有该用户时，先注册：

```http
POST {{baseUrl}}/api/v1/auth/register
Content-Type: application/json
```

```json
{
  "username": "demo_user_2026",
  "password": "DemoPass2026"
}
```

登录请求的ApiPost后置脚本：

```javascript
apt.variables.set("token", response.json.data.accessToken);
```

### 5.4 创建3设备RSMA场景

```http
POST {{baseUrl}}/api/v1/scenarios
Authorization: Bearer {{token}}
Content-Type: application/json
```

```json
{
  "name": "GRPO演示-3设备RSMA",
  "description": "预训练GRPO吞吐量全链路演示",
  "objective": "THROUGHPUT",
  "config": {
    "deviceCount": 3,
    "antennaCount": 1,
    "timeSlotCount": 100,
    "dataArrivalRate": 3,
    "averageGreenEnergy": 6,
    "batteryCapacity": 12,
    "dataBufferCapacity": 3,
    "wptTransmitPower": 4,
    "deviceMaxTransmitPower": 100,
    "accessScheme": "RSMA",
    "randomSeed": 2026
  }
}
```

后置脚本：

```javascript
apt.variables.set("scenarioId", response.json.data.id);
```

单位口径：WPT功率为W，设备最大发射功率为mW，绿色能量和电池为mJ，数据为Mb。

### 5.5 提交GRPO任务

每次演示必须使用新的`Idempotency-Key`。例如：

```http
POST {{baseUrl}}/api/v1/tasks
Authorization: Bearer {{token}}
Idempotency-Key: demo-grpo-20260721-rehearsal-001
Content-Type: application/json
```

```json
{
  "scenarioId": {{scenarioId}},
  "algorithm": "GRPO",
  "trainingConfig": {
    "maxTrainingSteps": 1000,
    "learningRate": 0.0003,
    "batchSize": 64,
    "discountFactor": 0.99,
    "randomSeed": 2026
  },
  "priority": 5
}
```

`trainingConfig`是任务契约的历史命名；当前阶段只推理，不会因为该字段存在就在线训练。

后置脚本：

```javascript
apt.variables.set("taskId", response.json.data.id);
```

### 5.6 查询终态和结果

```http
GET {{baseUrl}}/api/v1/tasks/{{taskId}}
Authorization: Bearer {{token}}
```

等待：

```text
status = SUCCEEDED
progress = 100
```

然后查询：

```http
GET {{baseUrl}}/api/v1/tasks/{{taskId}}/result
Authorization: Bearer {{token}}
```

预期：

- `throughput`有值；
- `averageAoi=null`；
- `metrics.evaluationMode=PRETRAINED_MODEL`；
- `metrics.trainingPerformed=false`；
- `metrics.modelId=grpo-rsma-throughput-v1`；
- `metrics.checkpointSha256`为64位摘要；
- `metrics.baseSeed=2026`。

如果第一遍没有达到该终态，先排查问题，不进入截图运行。

## 6. 第二遍：关键状态截图取证

### 6.1 使用调试参数重新启动Java

停止第一遍Java和Python进程。Java使用以下参数重新启动：

```powershell
$env:SIMULATION_DISPATCH_MODE="rabbitmq"
$env:SIMULATION_WORKER_MODE="python"
$env:SIMULATION_WORKER_TOKEN="wireless-worker-local-secret"
$env:SIMULATION_OUTBOX_SCAN_INTERVAL="30s"
$env:SIMULATION_HEARTBEAT_TIMEOUT_SECONDS="600"
$env:SIMULATION_RECOVERY_SCAN_INTERVAL_MS="30000"
$env:RABBITMQ_HEARTBEAT="300s"
$env:JWT_ACCESS_TOKEN_TTL="60m"
mvn -s .mvn/settings.xml spring-boot:run
```

这些参数只用于观察中间状态：Outbox放慢到30秒，任务业务超时和RabbitMQ连接心跳延长，避免断点期间过早失败。

暂时不要启动Python Worker。

### 6.2 提交取证任务

可以复用已有场景，但必须换新的幂等键：

```http
Idempotency-Key: demo-grpo-20260721-evidence-001
```

提交后立即记录响应中的`taskId`。在数据库客户端执行：

```sql
SET @task_id = 这里替换成真实任务ID;
```

### 6.3 截图点A：任务与Outbox待发布

在30秒扫描窗口内执行：

```sql
SELECT
    id, task_no, algorithm, status, progress,
    retry_count, lock_version, submitted_at
FROM experiment_task
WHERE id = @task_id;

SELECT
    id, event_id, aggregate_id, event_type,
    attempt_no, status, publish_attempts,
    next_attempt_at, published_at, last_error
FROM outbox_event
WHERE aggregate_id = @task_id
ORDER BY id;
```

预期：

```text
experiment_task.status = PENDING
outbox_event.status     = PENDING
```

保存`docs/images/02-task-outbox-pending.png`。图片应同时显示任务ID和Outbox的`aggregate_id`，证明两条记录属于同一任务。

### 6.4 截图点B：Outbox已经发布、Python尚未消费

等待Outbox扫描完成，查询：

```sql
SELECT
    aggregate_id, status, publish_attempts,
    claimed_by, published_at, last_error
FROM outbox_event
WHERE aggregate_id = @task_id;
```

预期：

```text
status = PUBLISHED
publish_attempts >= 1
last_error = NULL
```

打开RabbitMQ主队列：

```text
simulation.task.execute.queue
```

预期：

```text
Ready   = 1
Unacked = 0
```

保存`docs/images/03-rabbitmq-ready.png`。不要点击`Get Message(s)`并使用不重新入队的方式取走消息。

### 6.5 启动Python调试并停在推理前

Python调试环境变量：

```powershell
conda activate pytorch
$env:SIMULATION_WORKER_TOKEN="wireless-worker-local-secret"
$env:GRPO_CHECKPOINT_PATH=(Resolve-Path "python-worker\pt\GRPO.pt").Path
$env:RABBITMQ_HEARTBEAT_SECONDS="300"
```

在`python-worker/worker/main.py`的下面一行设置断点：

```python
result = evaluate_grpo(**evaluation_kwargs(claim, self.config.checkpoint, str(output_dir)))
```

使用IDE调试运行`python-worker/worker/main.py`。程序停在该断点时，`claim`已经成功，但推理尚未开始。

### 6.6 截图点C：RUNNING与Unacked

执行：

```sql
SELECT
    id, status, progress, started_at,
    finished_at, error_message
FROM experiment_task
WHERE id = @task_id;

SELECT
    id, task_id, attempt_no, worker_id,
    status, heartbeat_at, started_at,
    finished_at, error_message
FROM task_execution
WHERE task_id = @task_id
ORDER BY attempt_no;

SELECT COUNT(*) AS result_count
FROM simulation_result
WHERE task_id = @task_id;
```

预期：

```text
experiment_task.status = RUNNING
task_execution.status  = RUNNING
result_count           = 0
```

保存`docs/images/04-task-running.png`。

此时RabbitMQ应显示：

```text
Ready   = 0
Unacked = 1
```

保存`docs/images/05-rabbitmq-unacked.png`。它证明消息已经交给消费者，但消费者尚未在业务完成前提前ACK。

### 6.7 截图点D：真实GRPO推理

继续运行Python。控制台应显示：

```text
checkpoint
device: cuda
environment: RSMA_ENV
episodes
Mean throughput
Std throughput
Min / Max
Summary JSON
```

保存`docs/images/06-grpo-inference.png`。

截图应保留模型、CUDA和吞吐量统计，裁掉本机用户名路径或其他隐私信息。不要把推理耗时描述成固定性能指标。

### 6.8 截图点E：四张表最终闭环

执行：

```sql
SELECT
    id, task_no, algorithm, status, progress,
    retry_count, started_at, finished_at
FROM experiment_task
WHERE id = @task_id;

SELECT
    aggregate_id, event_type, attempt_no,
    status, publish_attempts, published_at, last_error
FROM outbox_event
WHERE aggregate_id = @task_id
ORDER BY id;

SELECT
    id, task_id, attempt_no, worker_id,
    status, heartbeat_at, started_at,
    finished_at, error_message
FROM task_execution
WHERE task_id = @task_id
ORDER BY attempt_no;

SELECT
    id, task_id, throughput, average_aoi,
    convergence_step, JSON_PRETTY(metrics_json) AS metrics,
    artifact_path, created_at
FROM simulation_result
WHERE task_id = @task_id;
```

预期：

```text
experiment_task = SUCCEEDED / 100
outbox_event     = PUBLISHED
task_execution  = SUCCEEDED，只有一条本轮执行记录
simulation_result只有一条，throughput有值，average_aoi为NULL
```

保存`docs/images/07-database-result.png`。如果一个窗口放不下，可以先扩大结果区或组合两个裁剪画面，但必须保留同一个`task_id`。

RabbitMQ最终应恢复：

```text
Ready   = 0
Unacked = 0
```

### 6.9 截图点F：用户结果API

```http
GET {{baseUrl}}/api/v1/tasks/{{taskId}}/result
Authorization: Bearer {{token}}
```

保存`docs/images/08-result-api.png`。截图至少包含：

```text
throughput
averageAoi = null
evaluationMode = PRETRAINED_MODEL
trainingPerformed = false
modelId
checkpointSha256
baseSeed
```

不要截取Authorization页签中的完整JWT。

## 7. 自动化测试截图

### 7.1 Java测试

先停止正在运行的Java应用和Python Worker，避免Windows占用`target`目录；保持三个基础设施容器健康，然后执行：

```powershell
mvn -s .mvn/settings.xml clean test
```

保存`docs/images/09-java-tests.png`，只需保留最终汇总：

```text
Tests run: 97, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 7.2 Python真实权重测试

```powershell
$env:GRPO_TEST_CHECKPOINT=(Resolve-Path "python-worker\pt\GRPO.pt").Path
conda run -n pytorch python -m unittest discover -s python-worker\tests -v
```

保存`docs/images/10-python-tests.png`，保留：

```text
两次相同seed的吞吐量统计
Ran 10 tests
OK
```

如果没有配置`GRPO_TEST_CHECKPOINT`，复现测试会跳过，此时不能使用该截图宣称10项真实权重测试全部执行。

## 8. 截图质量与安全要求

- 统一使用PNG；
- 建议宽度1200—1600像素；
- 正文和关键数字在GitHub页面上可读；
- 单张尽量控制在500KB以内；
- 保留任务ID、状态、队列名、模型ID和业务指标；
- 隐藏JWT、Worker Token、数据库密码和其他账户信息；
- 裁掉个人用户名、无关磁盘路径、聊天窗口和无关应用；
- SHA-256不是密钥，可以保留；
- 不修改控制台或数据库结果来制造更好看的数字；
- 不把JAVA_MOCK结果截图当成GRPO科研结果。

## 9. 常见问题与恢复

### 9.1 登录后仍然401

检查受保护请求是否真正发送：

```http
Authorization: Bearer {{token}}
```

重新登录刷新Token；Java重启且未配置固定JWT密钥时，旧Token会失效。

### 9.2 Outbox一直PENDING

检查：

- Java是否仍在运行；
- `SIMULATION_DISPATCH_MODE`是否为`rabbitmq`；
- RabbitMQ是否健康；
- 是否为了截图设置了30秒扫描间隔但尚未到时间。

### 9.3 RabbitMQ一直Ready=1

检查Python Worker是否启动、vhost和账号是否正确，以及Java和Python是否使用相同Worker Token。

### 9.4 任务RUNNING但Python连接断开

长断点调试需要：

```powershell
$env:RABBITMQ_HEARTBEAT_SECONDS="300"
$env:SIMULATION_HEARTBEAT_TIMEOUT_SECONDS="600"
```

未ACK消息在连接断开后通常会重新入队；同一执行轮次重新领取可返回`RESUMABLE`。

### 9.5 任务被拒绝

真实GRPO第一版只接受：

```text
algorithm = GRPO
objective = THROUGHPUT
accessScheme = RSMA
deviceCount = 3
```

其他场景不会被权重强行加载。

### 9.6 同一个幂等键返回旧任务

这是幂等机制的预期行为。每次独立演示换一个新的`Idempotency-Key`，不要删除数据库约束或修改旧任务来绕过。

## 10. 最终验收清单

- [x] 第一遍无断点链路成功；
- [x] 三个基础设施容器健康截图清晰；
- [x] 任务与Outbox待发布状态属于同一个任务ID；
- [x] RabbitMQ Ready=1截图清晰；
- [x] RUNNING任务、执行记录和Unacked=1均已保存；
- [x] GRPO控制台显示真实权重、CUDA和吞吐量；
- [x] 四张表最终状态一致且只有一个业务结果；
- [x] 用户结果API包含完整模型元数据；
- [x] RabbitMQ空队列基线、消息Ready和消费中Unacked状态均已保存；
- [x] Java 97项测试通过截图；
- [x] Python 10项真实权重测试通过截图；
- [x] 所有图片均已检查隐私和可读性；
- [x] README只嵌入最关键的4张证据图。

完成全部勾选后，阶段10.2才可以正式关闭。

## 11. 2026-07-21截图审计记录

已收到并检查22张真实运行截图。链路证据完整覆盖：基础设施、场景、任务PENDING、Outbox发布、RabbitMQ Ready、任务与执行记录RUNNING、结果尚未产生、Unacked、CUDA GRPO推理、三张业务表终态、结果入库、用户结果API以及Java/Python测试。

README当前选用：

- `14.png`：RabbitMQ `Ready=0、Unacked=1`；
- `15.png`：CUDA预训练GRPO推理；
- `19.png`：吞吐量结果写入MySQL；
- `20.png`：用户结果API与模型元数据。

最终安全审计：

- `10.png`中的个人项目路径已经遮盖；
- `22-python-test.png`已经移除底部个人项目路径；
- `02.png`和`10.png`中的Worker Token是仅用于本地演示的公开值，允许随项目展示；
- 生产或公网部署必须替换演示Token，不得复用截图中的值。

全部截图的状态、指标、可读性和文件大小均符合阶段10.2要求，截图目录可以随项目提交，阶段10.2正式关闭。
