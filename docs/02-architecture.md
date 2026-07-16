# 架构草案

## 1. 分阶段架构

### MVP

```text
HTTP Client / Swagger
          |
          v
Spring Boot REST API
  |-- user module
  |-- security module
  |-- scenario module
  |-- task module
  |-- result module
          |
          v
        MySQL
```

### 异步任务阶段

```text
Spring Boot API -> MySQL
       |
       +-> Redis (cache / idempotency)
       |
       +-> RabbitMQ -> Python Worker -> result callback
```

## 2. 包结构原则

采用“按业务模块组织、模块内部分层”的结构，而不是把所有 Controller、Service、Mapper 分别堆在全局目录：

```text
com.chenmingqiang.wirelesssim
  common/
  system/
  user/
    api/
    application/
    domain/
    infrastructure/
  security/
  scenario/
    api/
    application/
    domain/
    infrastructure/
  task/
  result/
```

这样可以保持单体项目简单，同时为以后拆分服务保留清晰边界。

第一版不建立独立的`algorithm`业务模块。算法类型和训练参数属于一次实验任务的执行输入，随任务一起保存；只有出现可复用、可共享的算法模板需求时，才新增独立算法配置实体。

## 3. 已实现模块边界

- `user`：用户注册、登录数据与当前用户信息；
- `security`：Spring Security过滤器链、JWT签发与校验、401/403响应；
- `scenario`：仿真场景CRUD、类型化参数校验、JSON持久化、所有权与乐观锁；
- `task`：任务状态机、提交/查询/取消/重试API、应用服务、MyBatis持久层、快照、幂等和并发控制；
- `common`：统一响应、分页响应、业务异常和全局异常处理。

## 4. 关键设计问题

- 如何保证任务状态只能合法流转；
- 如何避免同一实验被重复提交；
- 数据库事务成功但消息发送失败如何处理；
- 消费者重复收到任务如何保持幂等；
- Worker 宕机后如何检测超时并恢复任务；
- 缓存与数据库如何保持可接受的一致性。

这些问题将在后续阶段逐个实现并记录取舍。
