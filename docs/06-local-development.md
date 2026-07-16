# 本地开发与数据库启动

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

## 3. 启动Java应用

项目使用完整的Oracle JDK 17.0.12。在IntelliJ中运行`WirelessSimApplication`，或在已经配置Java 17的终端中执行：

```powershell
mvn -s .mvn/settings.xml spring-boot:run
```

应用启动时，Flyway会自动检查并执行尚未应用的数据库迁移。

本地未配置`JWT_SECRET_BASE64`时，应用会生成一次性随机开发密钥，应用重启后旧Token会失效。部署环境必须通过环境变量提供固定的Base64密钥，不能把真实密钥提交到代码仓库。

## 4. 运行测试

日常单元测试：

```powershell
mvn -s .mvn/settings.xml test
```

数据库集成测试：

```powershell
mvn -s .mvn/settings.xml -Dtest=DatabaseMigrationIT test
```

数据库集成测试要求MySQL容器处于健康状态。

## 5. 停止环境

```powershell
docker compose stop mysql
```

该命令只停止容器，不删除数据库数据卷。不要随意执行带`-v`的删除命令。
