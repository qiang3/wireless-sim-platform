# MVP API契约

统一前缀：`/api/v1`

## 1. 统一约定

- 除注册、登录和系统存活检查外，业务接口必须携带`Authorization: Bearer <JWT>`。
- 成功响应使用`ApiResponse<T>`：`{"code":"OK","message":"success","data":...}`。
- 分页响应包含`content`、`page`、`size`、`totalElements`和`totalPages`。
- 参数错误返回400，未登录返回401，无权限返回403，资源不存在返回404，并发或业务冲突返回409。
- 错误响应包含稳定业务错误码和可读消息，不向客户端暴露堆栈。
- 创建成功返回201；删除或归档成功返回204；后续异步任务受理计划返回202。

## 2. 系统、认证与用户

| 方法 | 路径 | 认证 | 用途 |
|---|---|---|---|
| GET | `/system/ping` | 否 | 服务存活检查 |
| POST | `/auth/register` | 否 | 注册用户 |
| POST | `/auth/login` | 否 | 登录并取得访问令牌 |
| GET | `/users/me` | 是 | 返回当前用户ID、用户名和角色 |

管理员探针仅用于验证角色权限，不作为正式业务API。

## 3. 仿真场景（已实现）

| 方法 | 路径 | 成功状态 | 用途 |
|---|---|---:|---|
| POST | `/scenarios` | 201 | 新建本人场景 |
| GET | `/scenarios?page=0&size=20` | 200 | 分页查询本人未归档场景 |
| GET | `/scenarios/{id}` | 200 | 查询本人未归档场景详情 |
| PUT | `/scenarios/{id}` | 200 | 按版本号修改本人场景 |
| DELETE | `/scenarios/{id}` | 204 | 软归档未被任务引用的本人场景 |

### 3.1 创建场景请求

```json
{
  "name": "吞吐量基准场景",
  "description": "用于GRPO与PPO对比",
  "objective": "THROUGHPUT",
  "config": {
    "deviceCount": 3,
    "antennaCount": 4,
    "timeSlotCount": 10000,
    "dataArrivalRate": 2.5,
    "averageGreenEnergy": 5.0,
    "batteryCapacity": 20.0,
    "dataBufferCapacity": 1.5,
    "wptTransmitPower": 10.0,
    "deviceMaxTransmitPower": 1.0,
    "accessScheme": "RSMA",
    "randomSeed": 20260716
  }
}
```

枚举范围：

- `objective`：`THROUGHPUT`、`AOI`；
- `accessScheme`：`RSMA`、`NOMA`、`FDMA`。

核心校验范围：设备数1—1000、天线数1—128、时隙数1—10000000；到达率和容量、功率等必填数值按DTO边界校验。

### 3.2 更新场景请求

更新请求在创建字段基础上增加必填`version`：

```json
{
  "version": 0,
  "name": "吞吐量扩展场景",
  "description": "增加设备数量后的对比实验",
  "objective": "THROUGHPUT",
  "config": {
    "deviceCount": 5,
    "antennaCount": 4,
    "timeSlotCount": 10000,
    "dataArrivalRate": 2.5,
    "averageGreenEnergy": 5.0,
    "batteryCapacity": 20.0,
    "dataBufferCapacity": 1.5,
    "wptTransmitPower": 10.0,
    "deviceMaxTransmitPower": 1.0,
    "accessScheme": "RSMA",
    "randomSeed": 20260716
  }
}
```

更新SQL同时匹配`id + owner_id + version + archived=0`，成功后`version = version + 1`。旧版本更新返回409和`SCENARIO_VERSION_CONFLICT`。

### 3.3 场景响应

```json
{
  "code": "OK",
  "message": "success",
  "data": {
    "id": 1,
    "name": "吞吐量基准场景",
    "description": "用于GRPO与PPO对比",
    "objective": "THROUGHPUT",
    "config": {},
    "version": 0,
    "createdAt": "2026-07-16T18:00:00",
    "updatedAt": "2026-07-16T18:00:00"
  }
}
```

### 3.4 场景错误码

| HTTP状态 | 错误码 | 含义 |
|---:|---|---|
| 400 | `VALIDATION_ERROR` | DTO字段或分页参数超出约束 |
| 400 | `INVALID_REQUEST_FORMAT` | JSON格式错误或枚举值无法解析 |
| 401 | 认证错误码 | 未携带、无效或过期JWT |
| 404 | `SCENARIO_NOT_FOUND` | 场景不存在、已归档或不属于当前用户 |
| 409 | `SCENARIO_VERSION_CONFLICT` | 更新使用了旧版本号 |
| 409 | `SCENARIO_IN_USE` | 场景已被任务引用，不能归档 |

对他人资源统一返回404，避免泄露资源是否存在。

## 4. 实验任务（已实现）

| 方法 | 路径 | 成功状态 | 用途 |
|---|---|---:|---|
| POST | `/tasks` | 202 | 以场景、算法和训练参数提交任务 |
| GET | `/tasks` | 200 | 按状态和算法分页查询本人任务 |
| GET | `/tasks/{id}` | 200 | 查询本人任务详情、快照和执行状态 |
| POST | `/tasks/{id}/cancel` | 200 | 按版本号取消允许取消的任务 |
| POST | `/tasks/{id}/retry` | 200 | 按版本号重试失败任务 |

### 4.1 提交任务

请求头必须携带长度不超过64字符的`Idempotency-Key`：

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

- `algorithm`支持`GRPO`、`PPO`；
- `priority`可省略，默认值为5，范围1—10；
- 任务初始状态为`PENDING`；
- 创建时保存场景名称、描述、优化目标、配置和版本快照；
- 新请求与同键同参数重放均返回202和同一任务；
- 同一用户使用相同键提交不同参数，返回409和`IDEMPOTENCY_KEY_REUSED`。

### 4.2 分页查询

```http
GET /api/v1/tasks?page=0&size=20&status=PENDING&algorithm=GRPO
```

`status`和`algorithm`可选，只查询当前用户任务，按创建时间和ID倒序。

### 4.3 取消与重试

两个操作都要求请求体携带客户端最近查询到的任务版本：

```json
{
  "version": 0
}
```

- 取消遵循任务状态机，成功后状态为`CANCELLED`并增加版本；
- 重试只允许`FAILED -> QUEUED`，同时增加`retryCount`并清理旧错误信息；
- 版本过期返回`TASK_VERSION_CONFLICT`；
- 达到最大重试次数返回`TASK_RETRY_LIMIT_EXCEEDED`。

### 4.4 任务错误码

| HTTP状态 | 错误码 | 含义 |
|---:|---|---|
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 未提供或只提供空白幂等键 |
| 400 | `INVALID_IDEMPOTENCY_KEY` | 幂等键超过64字符 |
| 400 | `VALIDATION_ERROR` | 任务、训练参数、分页或版本参数不合法 |
| 404 | `TASK_NOT_FOUND` | 任务不存在或不属于当前用户 |
| 404 | `SCENARIO_NOT_FOUND` | 来源场景不存在、已归档或不属于当前用户 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 相同键用于不同任务参数 |
| 409 | `TASK_STATUS_CONFLICT` | 当前状态不允许取消或重试 |
| 409 | `TASK_VERSION_CONFLICT` | 并发状态更新发生版本冲突 |
| 409 | `TASK_RETRY_LIMIT_EXCEEDED` | 已达到最大重试次数 |

## 5. 实验结果

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/tasks/{id}/result` | 查询本人任务的吞吐量、AoI和收敛信息 |
| GET | `/tasks/{id}/artifacts` | 后续阶段获取训练曲线、日志等文件清单 |

`GET /api/v1/tasks/{id}/result`成功响应中的`data`示例：

```json
{
  "taskId": 1,
  "throughput": 63.07961128,
  "averageAoi": 0.18147605,
  "convergenceStep": 753,
  "deterministicSeed": 3327,
  "simulationMode": "JAVA_MOCK",
  "scientificResult": false,
  "artifactPath": null,
  "createdAt": "2026-07-16T23:05:30.000"
}
```

任务结果尚未产生、任务不存在或任务不属于当前用户时，统一返回404和`TASK_RESULT_NOT_FOUND`，避免通过响应差异枚举他人任务。
