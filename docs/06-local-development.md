# 本地开发环境启动

## 1. 启动Docker Desktop

确认Docker引擎可用：

```powershell
docker info
```

## 2. 启动项目MySQL

```powershell
docker compose up -d mysql
docker compose ps mysql
```

项目数据库连接信息：

```text
地址：localhost
端口：13306
数据库：wireless_sim
用户：wireless
密码：wireless_dev
```

宿主机使用`13306`，是为了避开本机MySQL80占用的`3306`和Windows保留的`3307-3406`端口段。

## 3. 启动RabbitMQ

```powershell
docker compose up -d rabbitmq
docker compose ps rabbitmq
```

本地连接信息：

```text
AMQP地址：localhost:5672
管理页面：http://localhost:15672
虚拟主机：wireless_sim
用户：wireless
密码：wireless_dev
```

管理页面用于查看连接、通道、交换机、队列、消息数量和消费者。上述账号只用于本地开发，部署环境必须改用外部配置的安全凭据。

RabbitMQ使用Docker命名卷`wireless-sim-platform_wireless-sim-rabbitmq-data`保存持久化数据。当前Docker Desktop的自定义数据目录为`D:\WSL\DockerDesktopWSL`，主要镜像、容器层和命名卷都位于其中的`disk\docker_data.vhdx`虚拟磁盘内，而不是直接保存到项目源码目录。

## 4. 启动Redis

```powershell
docker compose up -d redis
docker compose ps redis
```

本地连接信息：

```text
地址：localhost
端口：16379
密码：wireless_dev
```

Redis使用`redis:7.4.2-alpine`，只保存5秒任务详情缓存和60秒限流计数。项目明确关闭RDB和AOF，不创建Docker命名卷；容器重建后数据丢失不会影响业务正确性，MySQL仍是任务事实来源。

手动连接：

```powershell
docker compose exec redis redis-cli -a wireless_dev --no-auth-warning
```

## 5. 启动Java应用

项目使用完整的Oracle JDK 17.0.12。在IntelliJ中运行`WirelessSimApplication`，或在已经配置Java 17的终端中执行：

```powershell
mvn -s .mvn/settings.xml spring-boot:run
```

应用启动时，Flyway会自动检查并执行尚未应用的数据库迁移。

本地未配置`JWT_SECRET_BASE64`时，应用会生成一次性随机开发密钥，应用重启后旧Token会失效。部署环境必须通过环境变量提供固定的Base64密钥，不能把真实密钥提交到代码仓库。

## 6. 运行测试

日常单元测试：

```powershell
mvn -s .mvn/settings.xml test
```

数据库集成测试：

```powershell
mvn -s .mvn/settings.xml -Dtest=DatabaseMigrationIT test
```

现有数据库集成测试要求MySQL容器处于健康状态；消息集成测试要求RabbitMQ健康；Redis专项集成测试要求Redis健康。普通测试在`src/test/resources/application.properties`中默认关闭Redis功能，专项测试会显式启用。

## 7. 停止环境

```powershell
docker compose stop mysql rabbitmq redis
```

该命令只停止容器，不删除数据库数据卷。不要随意执行带`-v`的删除命令。

## 8. 查看和人工处理最终死信

查看三个队列的待处理与未确认消息数：

```powershell
docker compose exec -T rabbitmq rabbitmqctl -p wireless_sim list_queues name messages_ready messages_unacknowledged
```

最终死信队列名称：

```text
simulation.task.dead.queue
```

在RabbitMQ管理页面中：

1. 打开`http://localhost:15672`并登录；
2. 选择`Queues and Streams`；
3. 打开`simulation.task.dead.queue`；
4. 在`Get messages`中先使用重新入队方式查看消息，避免检查时误删；
5. 核对`x-delivery-attempt`、`x-last-error`、`x-last-failed-at`和`x-final-failure`；
6. 同时查询MySQL任务当前状态与`retry_count`，确认死信是否仍然有效。

人工重新驱动前必须先修复根因。确认需要重放后，在管理页面的`Exchanges`中打开`simulation.task.exchange`，使用路由键`simulation.task.execute`重新发布原始JSON载荷和必要Header。随后再从最终死信队列确认并移除原消息。不要直接建立死信队列到主交换机的自动回流，否则永久错误会形成循环。
