# 阶段6：实验任务管理设计草案

状态：**已确认、已实现并通过15个自动化测试验收**。

## 1. 阶段目标

完成任务提交、分页查询、详情、取消、失败重试、所有权校验、幂等和并发控制。阶段6只管理任务，不真正执行仿真；阶段7再加入Java模拟Worker和结果闭环。

## 2. 现有数据库基础

`V1__init_schema.sql`已经创建`experiment_task`，包含：

- 标识：`id`、32位`task_no`；
- 关联：`scenario_id`、`creator_id`；
- 输入：`algorithm`、`training_config_json`、`priority`；
- 状态：`status`、`progress`、`retry_count`、`max_retry_count`；
- 一致性：`idempotency_key`、`lock_version`；
- 诊断和时间：`error_message`、提交/开始/结束/创建/更新时间；
- 唯一约束：`task_no`，以及`(creator_id, idempotency_key)`。

已经执行的`V1`不修改。确认新字段后使用`V2`迁移增量变更。

## 3. 建议新增字段

| 字段 | 类型建议 | 用途 | 状态 |
|---|---|---|---|
| `scenario_snapshot_json` | JSON NOT NULL | 保存任务提交时的场景参数，保证可复现 | 已实现 |
| `request_hash` | CHAR(64) NOT NULL | 保存规范化请求的SHA-256，识别相同键不同请求 | 已实现 |

建议快照同时包含场景名称、优化目标、场景配置和场景版本；`scenario_id`继续保留，用于追踪任务来源。

## 4. 建议领域模型

### 4.1 任务状态

复用已实现的`TaskStatus`：

```text
PENDING -> QUEUED -> RUNNING -> SUCCEEDED
   |          |          |-> FAILED -> QUEUED
   |          |          |-> CANCELLED
   |          |-> CANCELLED
   |-> CANCELLED
```

`SUCCEEDED`和`CANCELLED`是终态。所有状态修改使用`lock_version`进行乐观锁校验。

### 4.2 算法与训练参数

建议第一版只支持`GRPO`和`PPO`，不建立独立算法配置表。任务保存：

```json
{
  "algorithm": "GRPO",
  "trainingConfig": {
    "maxTrainingSteps": 100000,
    "learningRate": 0.0003,
    "batchSize": 64,
    "discountFactor": 0.99,
    "randomSeed": 20260716
  }
}
```

公共训练参数使用类型化DTO和Bean Validation；GRPO/PPO特有参数在确认真实实验代码后再决定是否进入第一版，避免为了丰富技术栈虚构无实际含义的字段。

## 5. 建议接口

### 5.1 提交任务

```http
POST /api/v1/tasks
Authorization: Bearer <JWT>
Idempotency-Key: <客户端生成的唯一键>
Content-Type: application/json
```

```json
{
  "scenarioId": 10,
  "algorithm": "GRPO",
  "trainingConfig": {
    "maxTrainingSteps": 100000,
    "learningRate": 0.0003,
    "batchSize": 64,
    "discountFactor": 0.99,
    "randomSeed": 20260716
  },
  "priority": 5
}
```

建议处理流程：

1. 从JWT读取`creator_id`；
2. 校验幂等键格式和请求DTO；
3. 查询并校验场景属于当前用户且未归档；
4. 生成规范化请求摘要；
5. 复制场景快照；
6. 创建`PENDING`任务并依赖数据库唯一约束防止并发重复；
7. 相同用户、相同键、相同摘要返回原任务；相同键、不同摘要返回409。

### 5.2 分页查询

```http
GET /api/v1/tasks?page=0&size=20&status=PENDING&algorithm=GRPO
```

只返回当前用户任务；`status`和`algorithm`为可选筛选条件，按创建时间和ID倒序。

### 5.3 查询详情

```http
GET /api/v1/tasks/{id}
```

返回任务号、来源场景、场景快照、算法、训练参数、状态、进度、重试次数、错误信息、版本和时间字段。不存在或不属于当前用户统一返回404。

### 5.4 取消任务

```http
POST /api/v1/tasks/{id}/cancel
```

按状态机校验是否允许取消，并通过`lock_version`防止并发状态覆盖。阶段6没有Worker，主要覆盖`PENDING/QUEUED`；阶段7以后`RUNNING`取消需要Worker协作停止，不能只修改数据库状态。

### 5.5 重试任务

```http
POST /api/v1/tasks/{id}/retry
```

仅允许`FAILED -> QUEUED`，要求`retry_count < max_retry_count`。建议保留同一个任务ID，增加`retry_count`；每次真实执行由`task_execution`新增`attempt_no`记录，避免把一次业务任务拆成多个无法关联的新任务。

## 6. 建议业务错误码

| HTTP状态 | 错误码 | 含义 |
|---:|---|---|
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 未提供幂等键 |
| 400 | `VALIDATION_ERROR` | 任务或训练参数不合法 |
| 404 | `TASK_NOT_FOUND` | 任务不存在或不属于当前用户 |
| 404 | `SCENARIO_NOT_FOUND` | 来源场景不存在、已归档或不属于当前用户 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 相同键对应了不同请求参数 |
| 409 | `TASK_STATUS_CONFLICT` | 当前状态不允许取消或重试 |
| 409 | `TASK_VERSION_CONFLICT` | 并发状态更新发生版本冲突 |
| 409 | `TASK_RETRY_LIMIT_EXCEEDED` | 已达到最大重试次数 |

## 7. 已确认并实现的关键决定

1. 保存`scenario_snapshot_json`。
2. 保留联合唯一约束并增加`request_hash`；同键同参数返回原任务，不同参数返回409。
3. 第一版只支持`GRPO`、`PPO`。
4. 公共训练参数使用类型化DTO，算法特有参数以后按真实实验代码扩展。
5. 新建与幂等重放均返回202并返回同一任务。
6. 重试复用原任务并增加重试计数；阶段7开始记录每次真实执行尝试。
7. 保留运行中取消的状态机能力，阶段7实现Worker协作后再完成真实停止语义。

验收结果：`TaskFlowTest` 4个测试通过；全项目15个测试通过，0失败、0错误、0跳过。
