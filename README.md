# 无线供能网络仿真实验与异步任务调度平台

这是一个以 Java 为主体的后端工程项目，用于管理无线供能通信网络的场景配置、算法配置、仿真实验任务和实验结果。

当前阶段只搭建最小可运行骨架。后续将按顺序加入 MySQL/MyBatis、Spring Security、Redis、RabbitMQ、Python 仿真执行器、测试与容器部署。

## 为什么做这个项目

- 把现有无线通信与强化学习科研代码工程化；
- 系统学习 Java 后端、数据库、缓存、消息队列、测试和部署；
- 形成能够用于央国企科技岗和软件开发岗的真实项目经历；
- 确保每一项写进简历的技术都经过实现、测试和复盘。

## 第一版核心对象

1. 用户 `User`
2. 仿真场景 `SimulationScenario`
3. 算法配置 `AlgorithmConfig`
4. 实验任务 `SimulationTask`
5. 实验结果 `SimulationResult`

## 计划中的任务状态

```text
PENDING -> QUEUED -> RUNNING -> SUCCEEDED
                         |----> FAILED
PENDING/QUEUED/RUNNING -------> CANCELLED
FAILED -----------------------> QUEUED (retry)
```

## 本地环境

- Java 17
- Maven 3.9+
- Docker Desktop（数据库和中间件阶段使用）
- Python 3.11（算法执行器阶段使用）

本机可暂时使用 IntelliJ 自带的 Java 17：

```text
C:\Program Files\JetBrains\IntelliJ IDEA 2023.2.4\jbr
```

在 IntelliJ 中将 Project SDK 设置为该目录即可。

## 命令行运行

PowerShell 中临时指定 Java 17：

```powershell
$env:JAVA_HOME='C:\Program Files\JetBrains\IntelliJ IDEA 2023.2.4\jbr'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test
mvn spring-boot:run
```

启动后访问：

```text
GET http://localhost:8080/api/v1/system/ping
GET http://localhost:8080/actuator/health
```

## 开发原则

- 先做单体、模块化后端，不提前拆微服务；
- 每个模块必须有测试和学习记录；
- 不为了简历堆技术，每个组件都要解决明确问题；
- 简历只写已经完成并能解释的功能。

更多内容见 `docs/`。

