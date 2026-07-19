# 学习与问题记录

每完成一个模块，按以下格式记录。最终简历和面试材料只能引用这里已经验证的内容。

## 模块记录模板

### 完成了什么

- 

### 为什么这样设计

- 

### 遇到的问题

- 

### 如何定位和解决

- 

### 如何测试

- 

### 面试可能追问

- 

### 面试参考回答

- 问题：
  - 回答要点：先给结论，再结合本项目说明实现，最后补充适用边界或改进方向。

## 2026-07-16：项目启动

### 已确认环境

- Maven 3.9.6
- Oracle JDK 17.0.12
- Docker 27.5.1 / Compose 2.32.4
- Python 3.11.5

### 第一项学习目标

理解 Spring Boot 应用的启动过程、Maven 项目结构、REST Controller 和最小化自动化测试。

## 2026-07-16：MySQL与数据库版本管理

### 完成了什么

- 使用 Docker Compose 启动 MySQL 8.4，宿主机端口为 `13306`。
- 接入 HikariCP、MyBatis、Flyway 和 MySQL Connector/J。
- 通过 `V1__init_schema.sql` 创建五张核心业务表。
- 编写数据库集成测试，检查核心表是否完整创建。

### 为什么这样设计

- MySQL 保存业务事实，后续 Redis 只承担缓存职责。
- Flyway 将表结构变化纳入代码版本管理，避免开发环境手工建表不一致。
- 数据库容器与本机已有 MySQL80 服务隔离，避免修改用户原有数据库。

### 遇到的问题

- 本机 MySQL80 已占用 `3306`。
- `3307` 位于 Windows 保留端口范围，Docker 无法绑定。
- Spring Boot 4 只引入 `flyway-core` 不会自动加载 Flyway 自动配置。

### 如何定位和解决

- 查询端口占用，确认 `mysqld.exe` 正在监听 `3306`。
- 查询 Windows 排除端口范围，选择可用的 `13306`。
- 按 Spring Boot 4 模块化依赖方式改用 `spring-boot-starter-flyway`，并保留 `flyway-mysql`。

### 如何测试

- `docker compose ps mysql` 检查容器健康状态。
- `mvn -Dtest=DatabaseMigrationIT test` 连接真实 MySQL 并验证五张核心表。
- 使用 MySQL 客户端执行 `SHOW TABLES` 进行二次确认。

### 面试可能追问

- Flyway解决了什么问题，为什么不手工执行SQL？
- 为什么业务状态放MySQL，而不直接放Redis？
- Docker端口映射中`13306:3306`两侧分别代表什么？
- 数据库迁移脚本已经上线后为什么不能随意修改？

### 面试参考回答

#### Flyway解决了什么问题，为什么不手工执行SQL？

Flyway解决的是数据库结构版本管理和多环境一致性问题。项目把建表及后续变更写成带版本号的迁移脚本，应用启动时Flyway会读取`flyway_schema_history`，只执行尚未应用的脚本。相比开发人员手工执行SQL，这种方式可以让本地、测试和生产环境按照同一顺序演进，也能明确追踪某次结构变化属于哪个版本。本项目使用`V1__init_schema.sql`创建五张核心表；后续如果增加字段，应新增`V2`脚本，而不是让不同环境分别手工修改。

#### 为什么业务状态放MySQL，而不直接放Redis？

MySQL是业务事实的持久化存储，提供事务、约束、索引和可靠落盘能力；Redis更适合缓存、分布式锁、限流或短期状态。实验任务状态、用户、场景和结果不能因为缓存重启或淘汰而丢失，所以本项目首先写入MySQL。后续可以把热点任务进度缓存到Redis以提升查询性能，但Redis中的数据应当能够从MySQL恢复，不能成为唯一事实来源。

#### Docker端口映射中`13306:3306`两侧分别代表什么？

左侧`13306`是宿主机端口，Java应用通过`localhost:13306`访问数据库；右侧`3306`是MySQL容器内部监听端口。这样不需要修改MySQL镜像内部配置，同时可以避开本机MySQL80已经占用的`3306`。不同容器可以继续在内部使用相同端口，只要映射到不同的宿主机端口即可。

#### 数据库迁移脚本已经上线后为什么不能随意修改？

Flyway会保存已执行脚本的版本和校验值。如果脚本已经在某个环境执行，再修改其内容，会导致新环境和旧环境得到不同的表结构，也可能触发校验失败。正确做法是保留已经执行的迁移脚本，再新增一个更高版本的脚本进行变更。例如上线后的`V1`需要增加字段，应新增`V2__add_xxx_column.sql`，而不是直接修改`V1`。只有尚未在共享环境执行的本地脚本才可以在确认影响后重写。

## 2026-07-16：Spring Security与JWT认证

### 完成了什么

- 使用Spring Security保护业务接口，注册和登录接口保持公开。
- 使用BCrypt保存密码哈希，不在数据库中保存明文密码。
- 使用Spring Security Resource Server与Nimbus签发、校验HS256 JWT。
- 在Token中保存`sub`、`user_id`和`role`等身份声明，访问令牌有效期为30分钟。
- 建立`USER`、`ADMIN`角色规则，并统一返回JSON格式的401和403错误。

### 为什么这样设计

- 使用Spring Security官方过滤器链，避免自行编写JWT过滤器造成验签、异常处理或上下文管理漏洞。
- 服务端采用无状态会话，每次请求通过Bearer Token恢复用户身份，便于后续水平扩展。
- 当前阶段只使用短期Access Token，暂不增加Refresh Token，控制首版复杂度。

### 遇到的问题

- `JwtTokenService`同时包含生产构造方法和便于测试的`Clock`构造方法，Spring无法自动判断注入入口。
- 未配置`JWT_SECRET_BASE64`时，应用每次启动会生成随机开发密钥，导致重启前签发的Token失效。

### 如何定位和解决

- 从Surefire报告中的第一处`Caused by`定位到`No default constructor found`。
- 为生产构造方法增加`@Autowired`，显式指定Spring依赖注入入口。
- 保留随机密钥作为本地开发兜底；部署环境必须通过环境变量提供固定密钥。

### 如何测试

- 端到端测试覆盖注册、BCrypt密码校验、重复用户名、登录签发JWT、Token鉴权、错误密码、401和403。
- 全量测试结果：8个测试全部通过，0失败、0错误。
- 测试结束后按随机用户名清理数据库记录，避免污染本地数据。

### 面试可能追问

- JWT由哪三部分组成，签名与加密有什么区别？
- BCrypt为什么不能直接解密，登录时如何校验密码？
- Spring Security如何把JWT中的`role`转换为`ROLE_USER`权限？
- 401和403分别代表什么场景？
- 为什么无状态Bearer Token接口可以关闭CSRF，什么情况下不能关闭？
- JWT如何失效，为什么后续可能需要Refresh Token或撤销机制？

### 面试参考回答

#### JWT由哪三部分组成，签名与加密有什么区别？

JWT通常由Header、Payload和Signature三部分组成。Header声明Token类型和签名算法，Payload保存`sub`、`exp`、角色等声明，Signature用于证明内容由可信服务签发且没有被篡改。签名不等于加密：普通JWT的Header和Payload只是Base64URL编码，拿到Token的人可以解码查看，因此不能放密码等敏感信息。本项目使用HS256和服务端密钥计算签名，校验时还检查过期时间与签发者。

#### BCrypt为什么不能直接解密，登录时如何校验密码？

BCrypt是带随机盐的单向密码哈希算法，不是可逆加密，所以数据库中的哈希不能还原成原密码。注册时使用`passwordEncoder.encode`生成哈希；登录时使用`passwordEncoder.matches`，它会从已保存哈希中读取盐和成本参数，对输入密码执行相同计算并比较结果。随机盐还能让相同密码产生不同哈希，降低彩虹表攻击的效果。

#### Spring Security如何把JWT中的`role`转换为`ROLE_USER`权限？

本项目在JWT中写入`role=USER`。资源服务器验签成功后，`JwtGrantedAuthoritiesConverter`读取`role`声明，并添加`ROLE_`前缀，得到`ROLE_USER`；管理员则得到`ROLE_ADMIN`。Spring Security中的`hasRole("ADMIN")`会自动检查`ROLE_ADMIN`，因此`/api/v1/admin/**`只允许管理员访问。这样Token中的业务角色与Spring Security的Authority模型完成了映射。

#### 401和403分别代表什么场景？

401表示认证失败，即系统无法确认请求者身份，例如没有Token、Token无效或已经过期。403表示身份已经确认，但当前用户没有访问资源的权限，例如普通`USER`携带有效Token访问管理员接口。本项目通过`AuthenticationEntryPoint`统一输出401，通过`AccessDeniedHandler`统一输出403，并返回一致的JSON结构。

#### 为什么无状态Bearer Token接口可以关闭CSRF，什么情况下不能关闭？

CSRF主要利用浏览器自动携带Cookie发起跨站请求。本项目不使用Session或认证Cookie，客户端必须主动把JWT放在`Authorization: Bearer`请求头中，第三方网站通常无法让浏览器自动附带这个请求头，因此当前纯无状态API可以关闭CSRF。这个结论有前提：如果以后把JWT放入Cookie、重新使用Session，或者同时提供浏览器表单认证，就必须重新启用并正确配置CSRF保护。

#### JWT如何失效，为什么后续可能需要Refresh Token或撤销机制？

当前Access Token在到达`exp`后自然失效，服务端更换签名密钥也会让旧Token全部失效。单纯无状态JWT签发后，服务端不会保存会话，因此用户主动退出、账号被禁用或Token泄露时，旧Token在过期前仍可能使用。可以通过缩短Access Token有效期降低风险，再使用Refresh Token换取新Token；如果需要立即撤销，可以引入Redis黑名单、Token版本号或撤销记录。Refresh Token本身也需要轮换、过期和安全存储，不能只延长有效期而不控制风险。

## 2026-07-16：仿真场景管理CRUD

### 完成了什么

- 实现场景创建、本人场景分页查询、详情查询、更新和软归档接口。
- 从JWT的`user_id`识别当前用户，所有SQL同时限制`owner_id`，阻止水平越权。
- 使用类型化DTO表达设备数、天线数、时隙数、绿色能量、缓存容量、功率、多址接入方式和随机种子，并使用Bean Validation校验嵌套参数。
- 将类型化配置序列化为MySQL JSON，响应时再反序列化为DTO。
- 更新场景时使用`version`实现乐观锁；归档时检查场景是否已被任务引用。
- 端到端测试覆盖完整CRUD、乐观锁冲突、软归档、跨用户访问、未认证访问和非法参数。

### 为什么这样设计

- 无线通信场景参数数量多且可能继续扩展，JSON字段减少首版频繁改表；接口仍使用类型化DTO，避免把任意JSON直接写入数据库。
- 资源查询直接使用`id + owner_id + archived=0`，既保证数据隔离，也避免向攻击者泄露他人资源是否存在。
- 场景是读多写少的数据，使用版本号乐观锁可避免无提示覆盖，同时不需要长期持有数据库锁。
- 归档使用逻辑状态而不是物理删除，保留审计和后续实验关联；当前规则禁止归档已被任务引用的场景。

### 遇到的问题

- 场景配置既需要保持无线通信业务含义，又不能把数据库设计成大量难以演进的零散字段。
- 更新接口需要区分“资源不存在”和“读取后被其他请求修改”。
- 测试必须经过JWT认证、Controller、Service、MyBatis和MySQL，不能只验证Controller返回固定值。

### 如何定位和解决

- 根据中期答辩材料确定第一版场景参数边界，采用“外部类型化DTO、内部JSON持久化”。
- 更新SQL把`id`、`owner_id`、`version`和`archived=0`放在同一个`WHERE`中；受影响行数为0时返回版本冲突。
- 使用MockMvc编写API集成测试，测试用户使用随机用户名，结束后清理场景和用户数据。

### 如何测试

- `ScenarioFlowTest`的3个测试覆盖：本人完整CRUD与版本冲突、跨用户访问隔离、认证与嵌套参数校验。
- 项目全量结果：11个测试通过，0失败、0错误、0跳过。
- 测试链路实际加载Spring上下文，并经过Spring Security、Controller、Service、MyBatis和MySQL。

### 面试可能追问

- 为什么场景配置使用JSON，是否意味着没有字段校验？
- 如何防止用户通过修改URL中的ID访问他人场景？
- 什么是乐观锁，为什么版本判断必须放进更新SQL？
- 软删除与物理删除各有什么取舍？
- MockMvc是什么，`perform`和`andExpect`分别做什么？
- 平时如何设计一个接口测试，所有测试是否都是开发者编写的？
- 场景参数快照和请求幂等分别解决什么问题？
- 联合唯一约束`(creator_id, idempotency_key)`是什么意思？

### 面试参考回答

#### 为什么场景配置使用JSON，是否意味着没有字段校验？

使用JSON是为了让科研仿真参数能够演进，避免每增加一个场景参数就修改表结构，但这不等于接受任意JSON。本项目的API使用`ScenarioConfigRequest`类型化DTO，并通过Bean Validation校验设备数、天线数、时隙数、容量、功率和枚举值；只有校验成功后才由ObjectMapper序列化并写入MySQL JSON字段。这样兼顾了接口类型安全与数据库扩展性。需要高频筛选、排序或建立索引的稳定字段仍应拆成普通列，不能把所有业务字段都放进JSON。

#### 如何防止用户通过修改URL中的ID访问他人场景？

服务端不会相信客户端传入的用户ID，而是从验签后的JWT中读取`user_id`。查询和更新SQL同时匹配资源ID与`owner_id`，例如`WHERE id=? AND owner_id=? AND archived=0`。即使用户修改URL中的场景ID，也无法匹配其他用户的数据。对不存在、已归档和不属于当前用户的资源统一返回404，避免泄露他人资源是否存在。

#### 什么是乐观锁，为什么版本判断必须放进更新SQL？

乐观锁假设并发冲突相对少，不在读取时长期锁住记录，而是在更新时检查客户端看到的版本是否仍是当前版本。本项目更新SQL包含`WHERE id=? AND owner_id=? AND version=?`，成功后执行`version=version+1`；受影响行数为0时返回409。不能先查询版本再执行不带版本条件的更新，因为两步之间仍可能有其他事务修改数据。把检查和更新放入同一条SQL，才能由数据库原子地完成并发控制。

#### 软删除与物理删除各有什么取舍？

物理删除直接移除记录，节省空间且查询逻辑简单，但不利于审计和恢复，也可能破坏历史关联。软删除通过`archived`标记隐藏记录，保留历史事实和外键关系，但所有查询都必须正确过滤归档数据，索引和唯一性也需要考虑归档状态。本项目的场景采用软归档，并在读取SQL中统一加`archived=0`。

#### MockMvc是什么？

MockMvc是Spring提供的Web层测试工具。它不需要真正启动浏览器或监听8080端口，却可以模拟HTTP请求，并让请求经过Spring Security、DispatcherServlet、Controller以及后续业务层。本项目使用`@SpringBootTest + @AutoConfigureMockMvc`，因此场景测试还会连接MyBatis和真实测试数据库，属于API集成测试，不是只测试一个Java方法的单元测试。

#### `perform`、`andExpect`和`andReturn`分别做什么？

`perform`负责执行一个模拟HTTP请求，例如GET或POST，并可添加请求头、Content-Type和JSON请求体；`andExpect`对同一个响应断言状态码或JSON字段，任何一个断言不满足都会让测试失败；`andReturn`在需要提取Token、任务ID等响应内容时返回`MvcResult`。可以概括为“`perform`发请求，`andExpect`验结果，`andReturn`取响应”。

#### 平时如何编写一个接口测试？

先把业务需求写成“给定条件—执行操作—预期结果”，再使用AAA结构：Arrange准备用户和数据，Act执行HTTP请求，Assert检查状态码、业务错误码和核心字段。每个接口至少考虑正常路径、参数边界、未认证、越权和业务冲突。测试之间应相互独立，使用确定的数据或随机唯一标识，并在结束后回滚或清理。完整流程测试适合验证业务闭环，模块变大后还应拆成更小的行为测试，便于失败定位。

#### 项目中的测试是否都是开发者自己编写的？

业务测试代码需要开发者根据需求主动编写，JUnit、MockMvc和Spring Test只提供执行、模拟请求和断言能力，不会自动知道业务正确答案。例如“旧版本更新应返回409”和“用户不能读取他人场景”都必须由开发者明确写成测试。框架负责自动重复执行这些规则，并报告失败位置。

#### 场景参数快照解决什么问题？

如果任务只保存`scenario_id`，场景在任务提交后被修改，任务执行时可能读到新参数，导致提交时的输入和实际执行输入不一致。参数快照是在创建任务时复制一份当时的场景配置，让后续修改不影响已经提交的任务，从而保证实验可追溯和可复现。`scenario_id`仍用于表示来源，快照用于保存当时事实。

#### 幂等键解决什么问题？

用户重复点击、客户端超时重试或网关重试都可能让同一个提交请求到达多次。客户端为一次业务操作生成`Idempotency-Key`，服务端以“用户ID+幂等键”识别同一次提交；相同键和相同请求应返回原任务，而不是创建重复任务。数据库唯一约束是并发下的最终防线，请求摘要可进一步识别“相同键却提交不同参数”的误用并返回409。

#### `UNIQUE KEY uk_task_creator_idempotency (creator_id, idempotency_key)`是什么意思？

这是联合唯一约束，唯一的是两个字段组成的组合，而不是要求每个字段单独唯一。同一用户不能重复使用同一个幂等键创建两个任务，不同用户可以使用相同字符串。它既能加快按组合查询，也能在两个并发请求同时通过Java层检查时，由数据库保证最终只有一条记录插入成功。

## 2026-07-16：实验任务管理、幂等与状态并发控制

### 完成了什么

- 使用`V2__add_task_snapshot_and_request_hash.sql`为任务表增加场景参数快照和SHA-256请求摘要，不修改已经执行的`V1`。
- 实现任务提交、按状态/算法分页查询、详情、取消和失败重试接口。
- 第一版支持`GRPO`和`PPO`，使用类型化DTO校验训练步数、学习率、批次大小、折扣因子和随机种子。
- 任务创建时保存场景名称、描述、优化目标、配置和版本快照，初始状态为`PENDING`。
- 使用`Idempotency-Key`、规范化请求摘要和数据库联合唯一约束实现重复提交保护。
- 使用`lock_version`保护取消和重试操作，复用已实现的`TaskStatus`状态机校验合法流转。
- 重试复用原任务，执行`FAILED -> QUEUED`，增加重试次数并清理旧错误信息。

### 为什么这样设计

- 任务保存场景快照，避免原场景修改后改变已经提交任务的输入，保证科研实验可追溯、可复现。
- 幂等键解决重复点击和网络重试；请求摘要区分“同一次请求重放”和“错误复用了同一个键”。
- Java层先查询可以快速返回原任务，数据库唯一约束负责解决两条请求真正并发时的竞态条件。
- 任务状态是读多写少且冲突概率有限的数据，使用版本号乐观锁比长时间持有数据库排他锁更合适。
- 同一个业务任务的重试保留同一任务ID，后续使用`task_execution`区分不同执行尝试，便于聚合状态和追踪历史。

### 遇到的问题

- 已经执行过的`V1`不能直接修改，但新增字段最终又要求`NOT NULL`。
- 相同幂等键可能对应相同请求，也可能被客户端错误地用于不同参数。
- 只在Java层执行“先查询、再插入”无法阻止真正并发的两个请求同时创建任务。

### 如何定位和解决

- `V2`先增加可空字段，为可能存在的旧任务回填快照和摘要，再改成`NOT NULL`，兼顾历史数据和新约束。
- 对请求的固定字段按确定顺序规范化，数值使用`stripTrailingZeros().toPlainString()`统一表示，再计算SHA-256。
- 为`(creator_id, idempotency_key)`保留联合唯一约束，并在捕获并发重复键后查询原任务、比较摘要。

### 如何测试

- `TaskFlowTest`覆盖创建、快照、分页、详情、取消和旧版本冲突。
- 覆盖相同键相同请求返回原任务、总数仍为1、相同键不同请求返回409、缺少幂等键返回400。
- 覆盖跨用户读取和取消返回404、未认证返回401、非法训练参数返回400。
- 通过数据库测试夹具构造`FAILED`状态，验证重试后变为`QUEUED`、计数增加、错误清理和超过上限返回409。
- 最终结果：任务模块4个测试通过；项目全量15个测试通过，0失败、0错误、0跳过。

### 面试可能追问

- 为什么任务既保存`scenario_id`，又保存场景快照？
- `Idempotency-Key`和`request_hash`分别解决什么问题？
- 为什么Java层查重后还需要数据库唯一约束？
- 请求摘要为什么要先规范化，为什么不保存完整请求作比较？
- 取消和重试为什么还需要`lock_version`？
- 为什么失败重试不创建一个新的业务任务？
- 新建和幂等重放为什么都返回202？
- `RUNNING -> CANCELLED`是否意味着数据库改状态后计算一定停止？
- 为什么使用新的`V2`迁移，而不是直接修改`V1`？

### 面试参考回答

#### 为什么任务既保存`scenario_id`，又保存场景快照？

`scenario_id`用于追踪任务来源和建立业务关联，快照用于保存提交时的实际输入。如果任务只保存ID，场景后续修改会导致执行或重试读到新参数，无法复现实验。本项目在任务创建事务中读取当前用户的有效场景，把名称、描述、优化目标、配置和版本序列化到`scenario_snapshot_json`；后续执行以快照为准，原场景仍可用于来源查询。

#### `Idempotency-Key`和`request_hash`分别解决什么问题？

幂等键标识客户端的一次业务操作，用于识别重复点击、超时重试或网关重放；请求摘要表示这次操作的实际参数。相同用户、相同键、相同摘要说明是同一请求重放，返回原任务；相同键但摘要不同说明客户端错误复用了键，返回409。只有幂等键而没有摘要，系统无法安全区分这两种情况。

#### 为什么Java层查重后还需要数据库唯一约束？

两条并发请求可能同时完成Java层查询，都看到“记录不存在”，随后一起插入。数据库的`UNIQUE KEY (creator_id, idempotency_key)`在存储层原子地保证只有一条能够成功，消除检查与插入之间的竞态窗口。Java层查询主要减少异常并快速响应，数据库约束才是最终一致性防线。

#### 请求摘要为什么要先规范化？

语义相同的十进制可能写成`0.3`、`0.30`或科学计数法，如果直接对原始JSON文本哈希，可能得到不同结果。本项目按固定字段顺序拼接，并用`stripTrailingZeros().toPlainString()`规范化小数后计算SHA-256，让语义相同的类型化请求得到稳定摘要。摘要固定为64个十六进制字符，比较和存储成本低；如需审计，完整输入已经分别保存在场景快照和训练参数JSON中。

#### 取消和重试为什么需要`lock_version`？

状态机只能说明某个状态理论上能否流转，不能阻止两条并发请求基于同一旧状态同时更新。本项目要求操作请求携带最近读取的版本，更新SQL同时匹配任务ID、用户ID、当前状态和`lock_version`，成功后版本加1。受影响行数为0表示数据已变化，返回409，避免取消、重试或后续Worker更新互相覆盖。

#### 为什么失败重试不创建新的业务任务？

重试仍然是同一个实验意图，只是新的执行尝试。如果每次重试都创建新任务，用户难以聚合状态、结果和失败历史。本项目保留原任务ID，增加`retry_count`并将`FAILED`流转到`QUEUED`；阶段7已经使用`task_execution`为每次执行增加`attempt_no`，既保持业务任务稳定，又完整记录每次尝试。

#### 新建和幂等重放为什么都返回202？

任务提交表示系统已经受理，但仿真尚未完成，因此使用202比201更符合异步语义。幂等重放返回同一个任务和相同状态码，让客户端可以把首次请求与网络重试按同一逻辑处理；如果客户端需要判断是否重放，可以通过任务ID或以后增加响应元数据实现，但不影响幂等结果。

#### `RUNNING -> CANCELLED`是否意味着计算已经停止？

不一定。数据库状态变为`CANCELLED`只表示控制面接受了取消，如果Worker没有协作检查取消标记，实际计算仍可能继续。阶段7已经实现协作式取消：Worker在每个模拟步骤边界检查任务状态，发现取消后停止后续计算、不保存结果，并把当前执行记录标记为`CANCELLED`。因此项目现在实现的是“有界延迟的协作取消”，而不是强制杀死线程。

## 2026-07-16：Java模拟执行、异步调度与结果闭环

### 完成了什么

- 使用MySQL轮询扫描`PENDING/QUEUED`任务，按优先级和提交时间分发到可配置的`ThreadPoolTaskExecutor`。
- 使用带状态条件的更新实现`PENDING -> QUEUED -> RUNNING`，并通过`(task_id, attempt_no)`唯一约束防止同一次尝试重复执行。
- 使用`task_execution`记录每次执行尝试的Worker、状态、心跳、开始结束时间和错误信息。
- 实现固定种子的`JAVA_MOCK`引擎，生成确定性的合成吞吐量、AoI和收敛步数，明确保存`scientificResult=false`。
- 将执行拆成10步，按10%更新进度，并在同一事务中刷新执行心跳。
- 实现运行中协作取消、异常失败、同任务手动重试和Worker心跳超时恢复。
- 在同一事务中完成任务成功、执行记录成功和结果插入，提供本人结果查询接口。
- 配置Maven Surefire执行`*Test`与`*IT`，最终全量26个测试通过。

### 为什么这样设计

- MySQL轮询能用现有基础设施快速打通完整闭环，便于先验证状态机、事务和异常补偿；阶段8再用RabbitMQ替换分发链路。
- 业务任务与执行尝试分离：任务表示用户的一次实验意图，执行记录表示初次执行或某次重试，避免历史被覆盖。
- 条件更新由数据库原子判断当前状态，能解决多个Worker同时领取同一任务的竞态；唯一约束提供第二道存储层防线。
- 进度和心跳属于同一次Worker活动，应一起提交或一起回滚；但它们不增加`lock_version`，避免频繁进度更新让用户取消请求不断过期。
- 取消采用Worker主动检查，而不是不安全地强制终止Java线程；最多延迟一个模拟步骤即可停止。
- 成功状态和结果必须原子提交，否则可能产生“任务成功但无结果”或“有结果但任务仍运行”的矛盾状态。
- 超时恢复更新SQL再次检查心跳截止时间，避免扫描之后Worker刚恢复心跳却仍被误判失败。

### 遇到的问题与解决方式

- 相邻轮询可能重复把同一任务提交到内存队列：单实例使用线程安全的`inFlightTaskIds`去重，多实例最终依赖数据库条件更新互斥。
- 线程池满时不能静默丢任务：捕获拒绝异常、释放本地占用标记、保留数据库`QUEUED`状态，等待下一轮扫描。
- 成功结果新增外键后，测试清理顺序错误：改为先删除`simulation_result`，再删除执行记录、任务、场景和用户。
- 第一次`mvn clean test`只执行20个`*Test`，漏掉4个`*IT`测试类：在Surefire中明确加入`**/*IT.java`，最终真正执行26个测试。

### 面试可能追问

- 为什么当前先使用MySQL轮询，而不是直接使用RabbitMQ？
- 多个Worker如何保证同一个任务只执行一次？
- 为什么既有`experiment_task`又有`task_execution`？
- 进度与心跳为什么放在同一个事务，为什么不增加`lock_version`？
- 协作式取消如何工作，能否做到立即停止？
- 为什么任务成功、执行成功和结果插入必须在同一个事务？
- 心跳超时恢复如何避免误杀正常Worker？
- 线程池队列满了如何处理？
- 为什么`JAVA_MOCK`不能宣称GRPO优于PPO？
- 为什么`mvn clean test`最初没有执行`*IT`？

### 面试参考回答

#### 为什么先使用MySQL轮询，而不是直接使用RabbitMQ？

当前阶段的目标是先验证异步任务的状态机、并发抢占、进度心跳、取消、结果和失败恢复闭环。MySQL已经是项目基础设施，使用条件更新和执行记录即可完成可靠的第一版，学习和调试成本更可控。它的缺点是持续轮询数据库、实时性和水平扩展能力有限，因此阶段8会引入RabbitMQ、Outbox、发布确认、手动ACK和消费幂等，但仍复用本阶段验证过的数据库状态机作为最终事实来源。

#### 多个Worker如何保证同一个任务只执行一次？

Worker使用`UPDATE ... WHERE status='QUEUED'`抢占任务，数据库会对并发更新进行串行化，只有一个Worker能把受影响行数更新为1，其他Worker得到0并放弃执行。同时`task_execution`对`(task_id, attempt_no)`建立唯一约束，即使应用层出现遗漏，数据库也不允许同一次尝试创建两条执行记录。单实例的`inFlightTaskIds`主要减少重复入内存队列，条件更新和唯一约束才是最终一致性保证。

#### 为什么区分业务任务和执行记录？

`experiment_task`表示用户的一次实验意图，保存场景快照、算法、训练参数和当前聚合状态；`task_execution`表示一次实际运行，保存尝试号、Worker、心跳、开始结束时间和错误。失败重试仍属于同一个实验，所以任务ID不变，但新增`attempt_no`。这样既方便用户查询一个稳定任务，也能保留每次失败和重试历史。

#### 进度和心跳为什么使用同一个事务？

进度变化和心跳刷新共同表示“Worker完成了一个步骤并且仍然存活”。如果进度提交但心跳失败，恢复器可能看到新进度和旧心跳并误判失联。本项目将两条更新放在同一事务中，心跳更新失败会抛出异常，使前面的进度更新回滚。它们不增加`lock_version`，因为高频进度写入不是业务状态决策，若每次都增加版本，会让用户携带的取消版本快速过期。

#### 协作式取消能否立即停止？

不能保证零延迟。用户请求先通过条件更新把任务标记为`CANCELLED`，Worker在每个模拟步骤前以及完成计算前检查状态，发现取消就停止后续步骤、不保存结果，并确认执行记录取消。因此最大取消延迟约为一个步骤耗时。Java没有安全的通用方式强制杀死正在运行的业务线程，协作式检查更可控，也能正确释放资源和维护数据库状态。

#### 为什么成功状态和结果保存必须使用一个事务？

成功包含三个不可分割的事实：任务成功、当前执行尝试成功、结果已经保存。如果分开提交，任何中间故障都可能产生部分成功。本项目在一个事务中条件更新任务、更新执行记录并插入结果；任一步失败都会整体回滚。成功更新仍带`status='RUNNING'`条件，因此它还会与用户取消竞争，最终只有成功或取消一方能够提交。

#### 心跳超时恢复如何避免误判？

恢复器先查询超过阈值的`RUNNING`执行记录，但查询结果可能在后续更新前已经被Worker刷新。因此真正标记失败的更新SQL再次携带“状态仍为RUNNING且心跳仍早于截止时间”的条件。受影响行数为0说明它已经恢复，不处理；只有二次条件仍成立时，才在事务中同时将执行记录和任务标记为`FAILED`。

#### 线程池满了怎么办？

线程池使用有界队列和`AbortPolicy`，避免无界堆积导致内存风险。提交被拒绝时，调度器记录告警并从`inFlightTaskIds`移除任务，但保持数据库状态为`QUEUED`，下一轮仍可重新提交。这样实现背压而不静默丢失。阶段8引入消息队列后，会进一步使用消息积压监控和消费限流。

#### 为什么JAVA_MOCK不能用于证明算法性能？

它只用场景参数和固定种子生成确定性合成指标，用于验证后端调度与结果链路，算法类型没有参与性能公式，也没有运行PyTorch训练。结果明确保存`simulationMode=JAVA_MOCK`和`scientificResult=false`。因此它只能证明工程闭环可运行，不能证明GRPO、PPO或其他算法的科研效果；真实对比必须等待Python/PyTorch Worker和规范实验。

#### 为什么最初的`mvn clean test`没有执行集成测试？

Surefire默认匹配`*Test`、`*Tests`和`*TestCase`，不会自动匹配`*IT`；因此第一次全量命令只运行了20个测试。项目后来在`pom.xml`中明确加入`**/*IT.java`，再次从空`target`目录执行后运行26个测试。这个问题说明不能只看`BUILD SUCCESS`，还要核对测试发现数量和测试类命名规则。

#### 为什么新增`V2`，而不是直接修改`V1`？

`V1`已经在本地数据库执行并由Flyway记录校验值，直接修改会导致已存在环境和新环境结构不一致，也会触发Flyway校验失败。本项目使用`V2`增量增加字段，并为可能存在的旧任务回填数据后再添加非空约束，使数据库演进过程可追踪、可重复执行。

## 2026-07-19：阶段8预研——Transactional Outbox与RabbitMQ

### 当前结论

- 阶段7的MySQL任务轮询已经完成业务闭环，阶段8不是推翻原实现，而是替换“任务如何被分发给Worker”这一段链路。
- MySQL继续保存任务、执行记录和结果，是最终事实来源；RabbitMQ负责可靠分发任务通知，不保存完整业务事实。
- 任务提交事务不直接同时写数据库和发送消息，而是在同一数据库事务中写入`experiment_task`和`outbox_event`。
- Outbox发布器只扫描未发布事件，将其发送至RabbitMQ；Worker通过RabbitMQ推送消费，不再反复扫描`QUEUED`业务任务。
- RabbitMQ采用手动ACK、数据库条件抢占和唯一约束实现“消息允许重复投递，但业务效果不能重复”。
- Redis只用于可以从MySQL恢复的加速能力，首选任务状态短期缓存和按用户提交限流，不替代MySQL，也不为已经由数据库条件更新解决的问题重复增加分布式锁。

### 面试可能追问

- 什么是Transactional Outbox，它解决了什么问题？
- 为什么`experiment_task`和`outbox_event`不能合并成一张表？
- 使用Outbox后为什么仍然需要扫描数据库？
- 既然仍要轮询，RabbitMQ相对阶段7有什么收益？
- RabbitMQ能否保证任务绝对只执行一次？
- 为什么消息中只放`eventId`和`taskId`，而不放完整场景和训练参数？
- 发布成功但更新Outbox状态失败怎么办？
- 消费者完成业务后、ACK之前宕机怎么办？
- 为什么阶段8不直接使用Redis分布式锁？

### 面试参考回答

#### 什么是Transactional Outbox，它解决了什么问题？

Transactional Outbox是把“业务数据变化”和“需要发送的事件”写入同一个本地数据库事务。本项目提交任务时，同时插入`experiment_task`和`outbox_event`：两条记录一起提交或一起回滚。事务提交后，再由独立发布器读取Outbox并发送到RabbitMQ。它解决的是数据库与消息队列之间无法使用普通本地事务原子提交的双写一致性问题，避免“任务已创建但消息没发出”或“事务回滚但消息已经发出”。

#### 为什么`experiment_task`和`outbox_event`不用同一张表？

两张表表达的生命周期不同。`experiment_task`是长期业务实体，保存用户实验意图、参数快照、状态和重试信息；`outbox_event`是短期集成事件，保存事件类型、载荷、发布时间和发布重试次数。一个任务以后可能产生“任务已创建、已取消、已完成”等多个事件，因此它们不是一对一关系。拆表还能让发布器只扫描体积小、索引明确的待发布事件，事件发布完成后可以归档或清理，而不污染任务业务字段。

#### 使用Outbox后为什么仍然需要扫描数据库？

Outbox保证事件先可靠落在数据库，但RabbitMQ不知道数据库里新增了事件，所以需要发布器发现待发送记录。第一版可以定时扫描`status='PENDING'`的Outbox；更低延迟的做法是事务提交后立即尝试发布，同时保留定时扫描兜底；更成熟的架构还可以用CDC读取数据库变更日志。Outbox的目标不是消灭所有轮询，而是保证业务事务与待发送事件不会丢失。

#### 既然仍要轮询，RabbitMQ相对阶段7有什么收益？

阶段7由每个应用实例不断扫描`PENDING/QUEUED`业务任务，再放入本机线程池；实例增加后会产生更多重复扫描和数据库抢占。阶段8只由发布器扫描结构简单、可索引和可清理的Outbox，消息进入RabbitMQ后，由Broker主动推送给消费者。RabbitMQ负责消费者负载均衡、prefetch限流、消息积压、ACK、重新投递、重试和死信隔离，也便于后续把Java应用与Python Worker部署成独立服务。因此它的主要价值是解耦、可靠分发和水平扩展，不保证单机低负载场景一定比阶段7更快。

#### RabbitMQ能否保证任务绝对只执行一次？

不能只依靠RabbitMQ实现端到端Exactly Once。网络超时或消费者在完成数据库事务后、发送ACK前宕机，都可能造成消息重新投递。本项目采用At-Least-Once投递语义，并让消费者通过`eventId`去重、`UPDATE ... WHERE status='QUEUED'`条件抢占、`(task_id, attempt_no)`唯一约束以及结果唯一约束保证业务幂等。准确表述是“消息可能被消费多次，但同一次任务尝试只产生一次有效业务结果”。

#### 为什么消息中不放完整业务参数？

消息只携带`eventId`、`taskId`、`attemptNo`、`eventType`、`schemaVersion`和`occurredAt`，Worker再根据`taskId`从MySQL读取任务快照。这样可以减少消息体积、避免消息中的参数与数据库事实不一致，也降低以后字段演进和敏感数据泄露风险。`attemptNo`用于阻止旧消息错误抢占后续业务重试。代价是消费者需要查询一次数据库，但本项目的任务执行本来就依赖数据库状态抢占，因此这个取舍更合适。

#### 发布成功但更新Outbox状态失败怎么办？

发布器下一轮会再次读取这条未发布事件并重复发送，所以消费者必须幂等。生产者确认只能证明Broker接收了消息，不能与本地Outbox状态形成一个原子事务。允许重复、禁止丢失，再由消费者去重，是Outbox常见且可恢复的处理方式。

#### 消费者完成业务后、ACK之前宕机怎么办？

RabbitMQ会重新投递未ACK消息。新的消费者再次处理时，数据库条件抢占或消费记录会发现该任务尝试已经被处理，从而跳过重复执行，然后正常ACK。因此ACK应当在本地业务事务成功提交之后发送；如果业务事务失败，则不应确认消息，而应按异常类型重试或送入死信队列。

#### 为什么阶段8不直接使用Redis分布式锁？

阶段7已经通过数据库条件更新和唯一约束实现跨实例任务抢占，它与任务状态处在同一个事实存储中，正确性更容易证明。此时再增加Redis锁会引入锁过期、续期、释放和Redis故障等额外一致性问题，却没有解决新的核心问题。Redis应先用于任务状态短期缓存和提交限流等明确的性能场景；只有出现数据库方案无法满足的跨资源互斥需求时，再评估分布式锁。

## 2026-07-19：RabbitMQ本地基础环境

### 完成了什么

- 在Docker Compose中增加官方`rabbitmq:4.3.2-management`镜像；
- 映射AMQP端口`5672`和管理页面端口`15672`；
- 创建本地用户`wireless`、虚拟主机`wireless_sim`和RabbitMQ命名卷；
- 使用`rabbitmq-diagnostics -q ping`配置容器健康检查；
- 验证容器健康、RabbitMQ版本4.3.2、管理HTTP接口、用户和虚拟主机。

### 为什么这样设计

- 使用带`management`的官方镜像，可以在本地直观看到后续创建的交换机、队列、绑定、消息和消费者；
- 固定精确版本避免浮动标签更新后环境行为突然变化；
- 使用独立虚拟主机隔离本项目资源，后续队列名称相同也不会与其他项目冲突；
- 命名卷让容器删除重建后数据仍可保留，源码目录不会出现RabbitMQ内部数据文件；
- 健康检查验证RabbitMQ节点能够响应，而不只是容器进程已经启动。

### 存储位置

- Docker逻辑卷名：`wireless-sim-platform_wireless-sim-rabbitmq-data`；
- 容器内挂载点：`/var/lib/rabbitmq`；
- Docker Linux环境中的卷目录：`/var/lib/docker/volumes/wireless-sim-platform_wireless-sim-rabbitmq-data/_data`；
- 本机Docker Desktop自定义数据目录：`D:\WSL\DockerDesktopWSL`；
- 实际Windows虚拟磁盘：`D:\WSL\DockerDesktopWSL\disk\docker_data.vhdx`。

虚拟磁盘当前约37.09GB，这是整个Docker环境的容量文件，包含MySQL、RabbitMQ及其他Docker数据，不代表RabbitMQ单独占用37.09GB。

### 面试可能追问

- RabbitMQ的5672和15672端口分别做什么？
- 为什么使用虚拟主机，虚拟主机是否等同于数据库？
- Docker命名卷和绑定挂载有什么区别？
- 为什么容器重建后RabbitMQ数据仍然存在？
- 容器显示`Up`与`healthy`有什么区别？

### 面试参考回答

#### 5672和15672分别做什么？

`5672`是AMQP客户端连接端口，后续Spring Boot通过它创建连接、通道并收发消息；`15672`是Management插件提供的HTTP管理端口，用于浏览器页面和管理API。应用正常运行不依赖浏览器页面，但本地调试可以用它查看交换机、队列、消费者和消息积压。

#### 为什么使用RabbitMQ虚拟主机？

虚拟主机是RabbitMQ内部的逻辑隔离边界，每个虚拟主机有独立的交换机、队列、绑定和权限。它不是MySQL数据库，但作用类似“为不同应用划分独立命名空间”。本项目使用`wireless_sim`，避免与本机其他RabbitMQ项目互相影响，也便于只给项目账号授予该虚拟主机权限。

#### Docker命名卷为什么能保留数据？

容器可写层会随容器删除而丢失，命名卷拥有独立于容器的生命周期。Compose把命名卷挂载到RabbitMQ的`/var/lib/rabbitmq`，节点元数据和持久化消息写入卷中，因此重建容器后再次挂载同一卷即可读取原数据。只有显式删除命名卷，例如执行带`-v`的Compose删除命令，数据才会被移除。

#### `Up`和`healthy`有什么区别？

`Up`只表示容器主进程仍在运行，不代表服务已经完成初始化或可以响应请求。`healthy`表示Docker按配置执行`rabbitmq-diagnostics -q ping`成功，RabbitMQ节点已经能够响应诊断命令。依赖服务应优先根据健康状态判断是否可用。

## 2026-07-19：Spring AMQP连接与可靠性基础配置

### 完成了什么

- 通过`spring-boot-starter-amqp`接入Spring AMQP，版本由Spring Boot 4.1.0统一管理；
- 使用`spring.rabbitmq.*`配置地址、账号、虚拟主机、连接超时和心跳；
- 开启`correlated` Publisher Confirm、Publisher Return和`mandatory`消息；
- 为后续消费者预设手动ACK、2个监听线程和`prefetch=1`；
- 关闭框架自动发布重试和监听重试，后续统一由Outbox及延迟重试队列控制；
- 增加`dispatch-mode=mysql/rabbitmq`配置，当前默认保持阶段7的MySQL调度；
- 新增`RabbitConnectionIT`，真实连接`amqp://wireless@127.0.0.1:5672/wireless_sim`并验证连接与Channel打开。

### 为什么只添加Starter

Spring Boot Starter不仅引入Spring AMQP、Spring Rabbit和RabbitMQ Java客户端，还根据`spring.rabbitmq.*`自动创建`CachingConnectionFactory`、`RabbitTemplate`、`RabbitAdmin`和默认监听容器工厂。项目不手写重复的连接工厂，可以继续使用Spring Boot的依赖管理、外部化配置、连接缓存和自动配置能力。

实际解析的核心版本包括：

- `spring-boot-starter-amqp:4.1.0`；
- `spring-rabbit:4.1.0`；
- `amqp-client:5.30.0`。

这些依赖已下载到`D:\MyProgramData\Maven\Repository`，没有在项目内或C盘创建重复仓库。

### 为什么同时需要Confirm、Return和mandatory

- Publisher Confirm回答“RabbitMQ Broker是否接管了消息”；
- Publisher Return回答“消息到达交换机后是否因为没有匹配绑定而无法路由”；
- `mandatory=true`要求无法路由的消息返回生产者，否则它可能被静默丢弃。

只有Broker Confirm为ACK并且消息没有被Return，Outbox发布器才能把事件标记为`PUBLISHED`。这些机制仍不能证明消费者已经处理，消费者处理结果由手动ACK负责。

### 为什么关闭Spring内置自动重试

本项目需要把每一次失败、下一次发送时间和最终死信状态持久化并可观察。如果同时启用Spring模板重试、监听器重试、Outbox重试和RabbitMQ重试队列，同一消息可能在多个层面叠加重试，次数和时间难以解释。因此第一版关闭框架内重试，后续由Outbox控制生产发布重试，由RabbitMQ延迟队列控制消费投递重试。

### 如何测试

- 定向测试`RabbitConnectionIT`：1个测试通过，日志明确创建到`wireless_sim`虚拟主机的连接；
- 全量执行`mvn -s .mvn/settings.xml clean test`：重新编译63个主源码文件和13个测试源码文件；
- 最终27个测试通过，0失败、0错误、0跳过；原有认证、场景、任务和Java模拟执行链路未回归。

### 面试可能追问

- Spring Boot加入AMQP Starter后自动配置了什么？
- `Connection`和`Channel`有什么区别，为什么通常复用连接并缓存Channel？
- Publisher Confirm与消费者ACK有什么区别？
- Publisher Return与`mandatory=true`为什么要配合？
- `prefetch=1`有什么作用，是否越小越好？
- 为什么项目关闭Spring内置重试？

### 面试参考回答

#### Connection和Channel有什么区别？

Connection对应一个到RabbitMQ Broker的TCP连接，创建成本较高；Channel是在同一连接上复用的轻量逻辑通道，发布、消费和队列声明等AMQP操作通过Channel完成。应用通常保持少量长连接，并使用多个缓存Channel支撑并发，避免每条消息都重新建立TCP连接。Spring Boot自动配置的`CachingConnectionFactory`负责这层复用。

#### Publisher Confirm和消费者ACK有什么区别？

Publisher Confirm发生在生产者与Broker之间，只说明Broker已经接管发布的消息；消费者ACK发生在消费者与Broker之间，说明应用已经完成该消息需要的处理，Broker可以删除这次投递。两者彼此独立：生产者收到Confirm时，消息可能仍在队列等待消费；消费者完成处理也不能反向替代生产者发布阶段的可靠性确认。

#### `prefetch=1`为什么适合当前项目？

仿真任务执行时间相对较长，`prefetch=1`让每个消费者同一时刻最多持有一条未ACK消息，避免一个消费者预先占住大量任务而其他消费者空闲，也形成自然背压。它不是所有系统的最优值：短小消息为了提高吞吐量通常会提高prefetch；本项目先保证任务公平分配和故障恢复清晰，后续再根据压测调整。

## 2026-07-19：RabbitMQ业务拓扑声明

### 完成了什么

- 新增`SimulationRabbitNames`，集中保存交换机、队列和路由键名称，避免生产者、消费者和测试重复手写字符串；
- 新增`SimulationMessagingProperties`，把重试等待时间、最大投递次数和最大优先级映射为类型化配置，并在启动阶段校验参数；
- 新增`SimulationRabbitTopologyConfiguration`，声明主执行、延迟重试和最终死信三组交换机与队列；
- 主执行队列配置`x-max-priority=5`；
- 重试队列配置`x-message-ttl=10000`、`x-dead-letter-exchange=simulation.task.exchange`和`x-dead-letter-routing-key=simulation.task.execute`；
- 拓扑配置仅在`simulation.execution.dispatch-mode=rabbitmq`时生效，默认MySQL模式继续保持阶段7行为；
- 新增`RabbitTopologyIT`，连接真实Broker并验证交换机、队列、参数和绑定；
- 全量执行`mvn -s .mvn/settings.xml clean test`，28个测试全部通过。

### 消息经过的路径

正常执行路径：

`生产者 -> simulation.task.exchange -> simulation.task.execute.queue -> 消费者`

临时失败后的延迟重试路径：

`消费者 -> simulation.task.retry.exchange -> simulation.task.retry.queue -> 等待10秒 -> DLX转发到simulation.task.exchange -> simulation.task.execute.queue`

不可恢复或超过重试次数后的路径：

`消费者 -> simulation.task.dlx -> simulation.task.dead.queue -> 人工检查`

重试队列故意不设置消费者。消息在队列中等待TTL到期后，由RabbitMQ的死信机制转发回主交换机。如果给重试队列配置普通消费者，消息会被立即取走，就失去了延迟等待的作用。

### 为什么使用交换机、队列和绑定三层结构

- 生产者把消息发送给交换机，不直接依赖某个具体队列；
- 交换机根据路由键和绑定决定消息进入哪个队列；
- 队列负责保存消息并把消息交给消费者；
- 以后增加独立Python Worker、监控队列或不同版本消费者时，可以调整绑定关系，而不必修改所有生产者。

### 持久化边界

交换机和队列的`durable=true`表示RabbitMQ重启后这些拓扑资源可以恢复，但它不等于消息一定持久化。消息本身还需要由生产者设置持久化投递模式；可靠链路还必须结合Publisher Confirm、Outbox、消费者手动ACK和数据库幂等。当前小步只完成拓扑，消息生产与消费闭环将在后续步骤实现。

### 面试可能追问

- Exchange、Queue、Binding和Routing Key分别负责什么？
- 为什么重试队列没有消费者？
- TTL和DLX如何共同实现延迟重试？
- `durable=true`是否代表消息绝对不会丢失？
- 为什么拓扑只在`dispatch-mode=rabbitmq`时声明？
- 为什么队列使用Classic Queue，而不是Quorum Queue？

### 面试参考回答

#### TTL和DLX如何实现延迟重试？

消费者遇到临时基础设施故障时，把消息发布到重试交换机，交换机再按路由键送入没有消费者的重试队列。消息等待队列配置的10秒TTL到期后成为死信，RabbitMQ根据该队列的`x-dead-letter-exchange`和`x-dead-letter-routing-key`把它重新投递到主交换机和主执行队列。这样不需要业务线程`sleep`，也不会在等待期间占用消费者线程。

#### `durable=true`是否代表消息绝对不会丢失？

不是。`durable=true`只保证交换机和队列的定义可以在Broker重启后恢复。消息还需要设置为持久化，生产者需要Confirm和Return判断Broker是否接管并成功路由；数据库到消息队列之间用Outbox防止双写丢失；消费者在业务事务提交后手动ACK，并通过幂等处理重复投递。可靠性来自这些机制的组合。

#### 为什么本地环境使用Classic Queue？

本阶段是单节点本地开发环境，Classic Queue配置简单，足够验证路由、TTL、死信和消费语义。Quorum Queue依赖多副本Raft机制，其高可用价值需要多个RabbitMQ节点才能体现。简历和面试中应准确描述为“持久化经典队列的单节点开发环境”，不能宣称已经实现RabbitMQ集群高可用。

#### 为什么拓扑按分发模式条件加载？

阶段8采用渐进迁移。默认`mysql`模式继续运行阶段7调度器，避免RabbitMQ业务链路尚未完成时影响已有功能；只有显式选择`rabbitmq`模式才创建业务拓扑。后续消息闭环验收后再切换默认模式，并保证MySQL调度器和RabbitMQ消费者不会同时抢占同一任务。

## 2026-07-19：V3 Outbox数据库迁移

### 完成了什么

- 新增`V3__create_outbox_event.sql`，没有修改已经执行过的`V1/V2`；
- 创建`outbox_event`，保存事件身份、业务尝试、JSON载荷、消息优先级、发布状态、重试信息、领取租约和审计时间；
- 使用`event_id`唯一约束保证一个事件ID不会重复；
- 使用`(aggregate_type, aggregate_id, event_type, attempt_no)`业务唯一约束保证同一任务尝试最多产生一条执行请求事件；
- 为待发布候选扫描和`SENDING`超时恢复分别创建组合索引；
- Flyway把真实MySQL从v2升级到v3，`flyway_schema_history`已记录迁移成功；
- 数据库迁移测试扩展为3项，全量30项测试通过。

### 为什么同时需要自增ID和UUID事件ID

`id`是MySQL内部主键，长度短、索引紧凑，适合稳定排序和批量扫描；`event_id`是跨数据库、RabbitMQ和日志使用的全局标识，可作为AMQP `messageId`和Publisher Confirm关联ID。两个字段解决的问题不同：内部存储使用递增主键，跨系统追踪使用UUID。

### 为什么没有给aggregate_id添加外键

`aggregate_id`与`aggregate_type`组合表示通用业务聚合，当前指向任务，未来也可能保存其他实体事件。一个字段无法根据`aggregate_type`动态引用不同表，因此不设置普通外键。当前任务和事件仍由同一个MySQL事务保证一起提交或回滚，业务唯一约束负责防止重复事件。

### 两个组合索引分别解决什么问题

- `idx_outbox_publish_candidate(status, next_attempt_at, created_at, id)`：发布器先按状态和下次发送时间过滤，再按创建时间及ID稳定领取一批事件；
- `idx_outbox_sending_recovery(status, claimed_at)`：补偿任务快速找到领取后长期没有完成的`SENDING`事件，并把它们恢复为可再次发布状态。

组合索引遵守最左前缀原则。查询如果跳过`status`直接只按`next_attempt_at`筛选，就不能充分利用第一个索引。

### 面试可能追问

- 为什么不能直接修改`V1__init_schema.sql`加入Outbox表？
- 为什么同时使用自增主键和UUID事件ID？
- 业务唯一约束与事件ID唯一约束有什么区别？
- 为什么`aggregate_id`没有外键？
- 为什么发布候选索引把`status`放在第一列？
- `PENDING`、`SENDING`和`PUBLISHED`分别表示什么？

### 面试参考回答

#### 为什么不能修改已经执行的Flyway迁移？

Flyway会在`flyway_schema_history`保存已执行脚本的版本和校验信息。部署过的`V1/V2`如果被修改，新环境与旧环境会得到不同演进历史，Flyway校验也可能失败。正确做法是保持历史迁移不可变，用新的`V3`描述增量变化，让所有环境按相同顺序从旧版本升级到新版本。

#### 两个唯一约束有什么区别？

`event_id`唯一约束保证同一个技术事件标识只出现一次；业务唯一约束保证即使代码错误生成了不同UUID，同一个任务、事件类型和执行尝试仍然只能产生一条执行请求。前者防止事件ID重复，后者保护业务语义。

#### 三种Outbox状态分别表示什么？

`PENDING`表示事件已经随业务事务可靠落库，等待发布或等待下次重试；`SENDING`表示某个发布器实例已经短事务领取该事件，正在事务外发送RabbitMQ；`PUBLISHED`表示Broker Confirm成功且消息没有被Return。`PUBLISHED`不代表消费者已经执行完成，消费结果仍由任务状态和消费者ACK表达。

## 2026-07-19：Outbox Java持久化模型与MyBatis Mapper

### 完成了什么

- 新增`OutboxStatus`枚举，把数据库中的`PENDING/SENDING/PUBLISHED`映射为类型安全的Java状态；
- 新增`OutboxEvent`普通Java对象，完整承接V3表的19个字段；
- 新增`OutboxEventMapper`接口，由Spring和MyBatis在运行时创建代理对象；
- 新增`OutboxEventMapper.xml`，实现待发布事件插入、按事件ID查询和按业务唯一键查询；
- `insertPending`只插入业务字段，状态、发布次数和下次发送时间使用数据库默认值；
- 新增真实MySQL集成测试，验证自增主键回填、JSON转换、枚举映射、默认值和业务唯一约束；
- 定向2项测试通过，全量32项测试通过。

### 一次insertPending调用如何执行

1. Spring扫描到`@Mapper`接口；
2. MyBatis创建实现该接口的代理对象并注入调用方；
3. 调用`insertPending(event)`时，MyBatis根据接口全限定名和方法名定位XML中的同名SQL；
4. `#{eventId}`等占位符通过预编译参数绑定读取Java对象属性；
5. MySQL执行插入并生成自增主键、`PENDING`状态和时间默认值；
6. `useGeneratedKeys=true`把数据库主键回填到`event.id`。

接口本身没有手写实现类，但运行时代理对象承担了“调用XML、绑定参数、执行SQL、映射结果”的工作。

### 为什么JSON字段使用CAST

Java层把事件载荷保存为字符串，SQL使用`CAST(#{payloadJson} AS JSON)`要求MySQL按JSON类型校验并存储。如果字符串不是合法JSON，插入会失败并触发事务回滚，避免把不可解析消息写入Outbox。查询时MySQL驱动再把JSON值返回为字符串，后续发布器可以直接反序列化或发送。

### 为什么insertPending不传status

新事件只能从`PENDING`开始，因此SQL不接收调用者传来的任意状态，而是使用V3中定义的数据库默认值。同理，`publish_attempts=0`和`next_attempt_at=CURRENT_TIMESTAMP(3)`由数据库生成。这样不同业务入口创建事件时不会忘记初始化字段，也不能误把新事件直接插成`PUBLISHED`。

### 面试可能追问

- MyBatis接口没有实现类，为什么可以注入并执行？
- `#{}`和`${}`有什么区别，为什么这里使用`#{}`？
- `useGeneratedKeys`和`keyProperty`做了什么？
- Java枚举如何映射VARCHAR状态？
- JSON为什么不直接作为普通VARCHAR保存？
- 为什么插入SQL不包含status和publish_attempts？

### 面试参考回答

#### MyBatis Mapper接口为什么不需要实现类？

应用启动时MyBatis扫描`@Mapper`接口，为它创建JDK动态代理对象并注册为Spring Bean。调用接口方法时，代理根据`namespace + 方法名`找到XML中的SQL，完成参数绑定、数据库调用和结果映射。因此注入到业务类中的实际对象是运行时代理，不是手写实现类。

#### `#{}`和`${}`有什么区别？

`#{}`会生成预编译SQL参数占位符，由JDBC安全绑定值，可以处理类型转换并降低SQL注入风险；`${}`是直接把文本拼入SQL，常用于无法参数化的受控标识符，但不能接收不可信输入。本项目所有事件字段都是数据值，因此使用`#{}`。

#### 为什么要分别测试事件ID唯一和业务唯一？

不同UUID只说明技术事件标识不同，不代表业务上允许重复。测试使用两个不同`event_id`但相同任务、事件类型和尝试号，确认数据库仍然抛出`DuplicateKeyException`，证明业务唯一约束确实是最后一道并发防线。

## 2026-07-19：任务与Outbox成功路径接入同一事务

### 完成了什么

- 新增`TaskExecutionRequestedMessage`，固定轻量消息的6个版本化字段；
- 新增`TaskOutboxEventFactory`，集中生成UUID、UTC发生时间、JSON载荷和消息优先级；
- `TaskService.submit`插入任务成功后，立即在同一个`@Transactional`方法中插入尝试1事件；
- `TaskService.retry`更新任务后重新读取`retry_count`，在同一事务插入下一尝试事件；
- 幂等重放在检测到已有任务后直接返回，不插入第二条事件；
- 业务优先级按`1/2→1、3/4→2、5/6→3、7/8→4、9/10→5`映射；
- 任务API测试已验证首次事件、幂等不重复和人工重试事件，全量32项测试通过。

### 为什么任务和事件属于同一个事务

`submit`和`retry`是由Controller通过Spring代理调用的公开方法，方法上已有`@Transactional`。方法内部先调用`TaskMapper`修改任务，再调用`OutboxEventMapper`插入事件，两次数据库操作使用同一个线程绑定的事务连接。只有整个方法正常返回时Spring才提交；任意运行时异常离开方法，Spring会回滚该连接上的全部修改。

`insertExecutionRequestedEvent`是同一个对象内部调用的私有方法，它不会经过新的Spring代理，也不会创建新事务，因此自然加入调用它的`submit/retry`事务。

### 为什么使用独立事件工厂

如果首次提交和人工重试各自拼接JSON，很容易出现字段遗漏、版本不一致或优先级映射不同。工厂把事件类型、结构版本、UUID、UTC时间和序列化集中管理，两个业务入口只传`taskId + attemptNo + priority`。以后消息升级时只需要修改一个位置并补充兼容测试。

### 为什么人工重试后重新读取任务

重试SQL在数据库中执行`retry_count = retry_count + 1`并使用乐观锁限制状态。更新成功后重新查询可以取得数据库最终的`retry_count`、`priority`和版本，再用`retry_count + 1`形成消息尝试号。这样尝试号不是靠Java猜测，能与任务持久化状态保持一致。

### 面试可能追问

- 同一个`@Transactional`方法调用两个Mapper，为什么是同一个事务？
- 私有方法上的数据库操作是否还在外层事务中？
- 为什么不在任务提交事务里直接调用RabbitMQ？
- 为什么事件工厂使用UTC时间和消息结构版本？
- 为什么幂等请求重放不能再创建Outbox事件？
- 业务优先级为什么需要映射为RabbitMQ队列优先级？

### 面试参考回答

#### 为什么不直接在任务事务中发送RabbitMQ？

MySQL本地事务不能原子控制RabbitMQ。先提交任务再发消息，进程可能在两步之间退出；先发消息再提交任务，事务回滚后消费者可能收到不存在的任务。项目选择在任务事务内只写MySQL任务和Outbox，事务提交后由独立发布器发送RabbitMQ。这样RabbitMQ暂时不可用也不会丢失待发送事件。

#### 私有方法为什么仍然在事务中？

事务上下文绑定在当前线程和数据库连接上，不要求每一层方法都重新标注`@Transactional`。`submit/retry`通过Spring代理开启事务后，内部私有方法调用Mapper仍使用同一个线程绑定连接。需要注意的是，私有方法自身即使标注`@Transactional`也不会通过代理产生新的传播行为；本项目正是有意让它沿用外层事务。

#### 幂等重放为什么不再创建事件？

同一幂等键和相同请求代表客户端重试同一次业务操作，应该返回原任务，而不是创建新执行尝试。原任务首次创建时已经拥有尝试1事件；再次插入既会违反业务唯一约束，也可能造成重复消息。只有用户对失败任务明确调用人工重试接口时，才递增`retry_count`并产生新的`attemptNo`事件。

## 2026-07-19：Outbox故障注入与事务回滚证明

### 完成了什么

- 新增`TaskOutboxTransactionIT`，使用真实`TaskService`、真实任务Mapper、真实MySQL和真实Spring事务管理器；
- 只通过`@MockitoBean`替换`OutboxEventMapper`，让`insertPending`稳定抛出运行时异常；
- 首次提交测试证明任务INSERT已经执行后，Outbox异常仍会让任务记录回滚；
- 人工重试测试证明状态UPDATE已经执行后，Outbox异常仍会让状态、重试次数、乐观锁版本和错误信息全部回滚；
- 通过`ArgumentCaptor`取得本次准备写入的事件ID，只检查该事件没有落库，避免受数据库其他测试数据影响；
- 定向2项和全量34项测试通过，阶段8.3原子性验收完成。

### 为什么只Mock Outbox Mapper

原子性测试的目标不是验证Mock对象，而是构造一个稳定、精确的失败点。如果任务Mapper也被Mock，就无法证明真实MySQL事务回滚。因此测试只替换最后一步Outbox插入，前面的任务INSERT或UPDATE仍然真实执行；异常离开`TaskService`后，再用独立`JdbcTemplate`查询数据库最终状态。

### 首次提交测试证明了什么

测试先让`TaskService.submit`执行真实任务INSERT，随后Mock Outbox Mapper抛出`IllegalStateException`。服务事务结束后：

- 按用户查询`experiment_task`数量为0；
- 捕获本次生成的`eventId`，查询`outbox_event`数量为0。

这证明数据库中不会留下“任务已经存在但没有事件”的部分成功状态。自增主键数字可能出现空洞，因为MySQL回滚通常不会回收已经分配的AUTO_INCREMENT值；主键连续性不属于业务正确性要求。

### 人工重试测试证明了什么

测试预置`FAILED/retry_count=0/lock_version=0`任务，让重试SQL真实执行后在Outbox位置失败。事务结束后查询仍然得到：

- `status=FAILED`；
- `retry_count=0`；
- `lock_version=0`；
- `error_message=原始执行失败`；
- 没有对应Outbox事件。

这证明一次SQL更新中的多个字段和后续事件插入都属于同一个事务，不会只回滚其中一部分。

### 为什么测试方法本身不加@Transactional

测试需要观察服务事务已经提交或回滚之后的数据库状态。如果测试方法自己开启一个外层事务，服务默认会加入测试事务，异常后的回滚标记和测试框架自动回滚可能掩盖真实边界，也可能让断言读取到同一未完成事务中的状态。本测试不使用测试事务，让每次`TaskService`调用独立经过生产环境相同的Spring事务代理，异常返回后再查询最终结果。

### 面试可能追问

- 如何证明两个数据库操作确实处于同一事务，而不只是看代码上的`@Transactional`？
- `@MockitoBean`替换了什么，哪些组件仍然是真实的？
- 为什么原子性测试不把所有Mapper都Mock掉？
- 为什么测试方法本身不使用`@Transactional`？
- 事务回滚后AUTO_INCREMENT为什么可能出现空洞？
- Checked Exception和Runtime Exception对Spring默认回滚规则有什么区别？

### 面试参考回答

#### 如何用测试证明事务原子性？

在事务最后一步注入确定性故障，让前面的真实SQL先执行，再让异常穿过Spring事务代理。方法返回后使用独立查询检查数据库最终状态：首次提交没有任务，人工重试的全部字段保持原值。相比只检查注解存在，这种测试直接验证了运行时事务管理器、数据源连接和异常回滚行为。

#### Spring默认遇到什么异常会回滚？

默认情况下，Spring声明式事务对`RuntimeException`和`Error`回滚，对普通Checked Exception默认不回滚。本项目Outbox插入和序列化失败都抛出运行时异常，因此会触发回滚。如果业务需要Checked Exception也回滚，应转换为运行时异常，或显式配置`rollbackFor`。

#### AUTO_INCREMENT出现空洞是否说明事务没有回滚？

不是。MySQL分配自增值后，即使事务回滚，已分配数字通常也不会重新使用，这是并发性能和唯一性设计的一部分。验证回滚应查询业务记录是否存在以及字段是否恢复，而不能要求主键序列连续。

## 问题：消费者如何通过数据库状态和业务唯一约束实现幂等？

这里的幂等是指：同一条RabbitMQ消息即使因为ACK丢失、消费者宕机或发布器重复发送而被处理多次，同一次任务尝试也只产生一次有效执行和结果。

阶段8.5消费者将使用三层防线：

1. 消息中的`attemptNo`必须等于任务当前的`retry_count + 1`，旧尝试消息不能抢占新重试；
2. 使用带状态条件的原子UPDATE抢占任务，例如同时匹配任务ID、允许执行的状态和尝试号，只有一个消费者能把任务改为`RUNNING`；
3. `task_execution`上的`UNIQUE(task_id, attempt_no)`作为最后数据库防线，即使应用并发判断出现问题，同一次尝试也不能插入两条执行记录。

任务抢占与执行记录插入需要处于同一个数据库事务。重复消息到达时可能出现：

- 任务已经是`RUNNING/SUCCEEDED/FAILED/CANCELLED`，条件UPDATE影响0行，消费者不再执行并直接ACK；
- 消息的`attemptNo`已经过期，条件UPDATE影响0行，直接ACK；
- 两个消费者同时处理同一消息，只有一个条件UPDATE成功，另一个影响0行；
- 极端情况下两个流程都尝试插入执行记录，唯一约束使其中一个失败并回滚。

因此项目保证的不是“消息绝对只送一次”，而是“消息可以重复到达，但同一次业务尝试只产生一次有效状态变化”。当前阶段8.3已经具备`attemptNo`与执行记录唯一约束；具体RabbitMQ消费者、条件抢占和手动ACK将在阶段8.5实现，面试时不能把待实现设计描述成已经完成。

## 2026-07-19：Outbox批量领取与超时租约恢复

### 本步完成内容

- 增加Outbox发布器类型安全配置及启动校验；
- 使用`FOR UPDATE SKIP LOCKED`批量锁定已到发送时间的`PENDING`事件；
- 在同一个短事务中写入`SENDING/claimed_by/claimed_at`并递增发布尝试次数；
- 使用2分钟租约恢复因进程崩溃而长期停留在`SENDING`的事件；
- 让查询排序与发布扫描索引保持一致；
- 通过真实MySQL并发测试和全量37项回归测试。

### 面试可能追问

- `FOR UPDATE SKIP LOCKED`是什么意思，为什么适合Outbox发布器？
- 为什么领取事务里不能直接等待RabbitMQ Confirm？
- `claimed_by`和`claimed_at`分别解决什么问题？
- 发布器领取事件后宕机，事件为什么不会永久卡死？
- 为什么查询按`next_attempt_at, created_at, id`排序？
- `publish_attempts`为什么在领取时递增，租约恢复时不减回去？

#### `FOR UPDATE SKIP LOCKED`是什么意思，为什么适合Outbox发布器？

`FOR UPDATE`会给查询到的候选记录加排他锁，使本事务可以安全地把它们改为`SENDING`。`SKIP LOCKED`表示遇到其他事务已经锁定的记录时直接跳过，不等待锁释放。多个应用实例因此能并行领取不同批次，同一事件不会被两个发布器同时领取。

#### 为什么领取事务里不能直接等待RabbitMQ Confirm？

RabbitMQ Confirm是网络操作，耗时不稳定。如果在持有数据库行锁的事务中等待Confirm，事务和连接会被长时间占用，其他发布器也更难领取事件。项目先在短事务中完成领取并提交、释放锁，再发送RabbitMQ；发送结果由后续独立数据库更新记录。

#### `claimed_by`和`claimed_at`分别解决什么问题？

`claimed_by`记录是哪一个发布器实例领取了事件，后续成功或失败更新必须校验领取者，防止旧实例误改新实例重新领取后的记录。`claimed_at`记录领取时间，用于判断发送是否超过租约并需要恢复。

#### 发布器领取事件后宕机，事件为什么不会永久卡死？

领取时事件变为`SENDING`并记录`claimed_at`。恢复任务定期寻找超过2分钟租约的`SENDING`事件，把它们恢复为`PENDING`、清空领取信息并允许再次发送。因此进程在领取后宕机只会导致延迟或重复发送，不会导致事件永久丢失。

#### 为什么查询按`next_attempt_at, created_at, id`排序？

`next_attempt_at`保证最早到达重试时间的事件优先，`created_at/id`提供稳定顺序。这个顺序还与索引`(status,next_attempt_at,created_at,id)`一致，可减少额外排序和不必要的行锁扫描，提高并发领取效率。

#### `publish_attempts`为什么在领取时递增，租约恢复时不减回去？

一次领取代表发布器已经开始了一次实际发布尝试，即使随后宕机、结果未知，这次尝试也真实发生过。恢复时保留次数能反映完整历史，并为后续指数退避和故障观测提供依据；把次数减回去会掩盖不确定发送和重复发布风险。

## 2026-07-19：单条Outbox消息的Publisher Confirm与Return

### 本步完成内容

- 把Outbox JSON载荷构造成UTF-8持久化AMQP消息；
- 设置事件ID、业务尝试号、消息版本、聚合信息、优先级和时间等属性；
- 使用`CorrelationData`等待Publisher Confirm；
- 使用mandatory Return识别交换机收到但无法路由的消息；
- 将结果统一分类为`ACK/RETURNED/NACK/TIMEOUT/SEND_FAILED`；
- 通过5项分支单元测试、1项真实RabbitMQ测试和43项全量回归。

### 面试可能追问

- Publisher Confirm和Publisher Return有什么区别？
- 为什么Confirm ACK后仍然要检查Return？
- 为什么消息需要设置为`PERSISTENT`？
- 为什么`messageId`和`CorrelationData.id`都使用`eventId`？
- Confirm ACK是否表示消费者已经执行成功？
- Confirm超时是否等于RabbitMQ没有收到消息？

#### Publisher Confirm和Publisher Return有什么区别？

Publisher Confirm回答“Broker是否接管了这次发布”。ACK表示Broker接受，NACK表示Broker拒绝。Publisher Return回答“交换机能否根据路由键把mandatory消息路由到至少一个队列”。因此它们检查的是两个不同阶段：Broker接收与交换机路由。

#### 为什么Confirm ACK后仍然要检查Return？

消息可能已经被交换机接收，因此得到Confirm ACK，但路由键没有匹配任何队列。这时mandatory机制会产生Return。如果只看ACK，就会把不可路由消息误标为发布成功。本项目在Confirm完成后优先检查同一个`CorrelationData`中的Return，只有“ACK且无Return”才判定成功。

#### 为什么消息需要设置为`PERSISTENT`？

持久化消息配合持久化交换机、持久化队列，可以让RabbitMQ在正常持久化和重启恢复流程中保留消息。只设置队列`durable=true`不够，因为durable只保证队列定义恢复，不自动让队列中的每条消息持久化。

#### 为什么`messageId`和`CorrelationData.id`都使用`eventId`？

`eventId`是一次业务事件的全局唯一标识。`messageId`随消息进入RabbitMQ和消费者，用于日志追踪与幂等；`CorrelationData.id`留在生产者侧，把异步Confirm/Return对应回具体Outbox事件。统一使用事件ID能贯通数据库、生产日志、Broker消息和消费日志。

#### Confirm ACK是否表示消费者已经执行成功？

不是。Confirm ACK最多证明Broker已经接管消息；无Return进一步证明消息路由到了队列。消费者是否收到、业务事务是否成功、是否ACK，是后续消费链路的问题，需要消费者手动ACK、数据库状态机和幂等约束保证。

#### Confirm超时是否等于RabbitMQ没有收到消息？

不等于。可能是消息未发送成功，也可能是Broker已经收到但确认在网络中丢失，或者应用等待超时。因此超时属于“结果未知”，可靠系统应保留事件并重试。这样可能产生重复消息，但不能冒险把未知结果标成成功并丢失任务；重复由消费者幂等处理。

## 2026-07-19：Outbox结果落库、定时发布与故障闭环

### 本步完成内容

- ACK更新`PUBLISHED`，失败恢复`PENDING`并写入错误；
- 使用5秒起步、每次翻倍、5分钟封顶的指数退避；
- 所有结果更新校验`id/status/claimed_by`；
- 每秒批量领取20条，逐条发送并落库；
- 独立定时恢复超过2分钟租约的`SENDING`事件；
- 验证真实成功、真实Return、NACK、超时、Broker异常、状态竞争和结果落库失败；
- 全量55项测试通过，阶段8.4关闭。

### 面试可能追问

- 为什么发布结果更新还要校验`claimed_by`？
- 指数退避如何计算，为什么需要封顶？
- 为什么网络发送和结果写库不能放在同一个事务？
- 消息发送成功但`PUBLISHED`更新失败会发生什么？
- 为什么调度器使用fixedDelay而不是fixedRate？
- 为什么MySQL JSON字段查询后的字符串顺序可能变化？

#### 为什么发布结果更新还要校验`claimed_by`？

某发布器领取后可能卡顿并超过租约，事件被恢复并由新实例重新领取。旧发布器之后才收到ACK，如果只按事件ID更新，就可能覆盖新实例正在处理的状态。本项目要求主键、`SENDING`和领取者同时匹配；旧结果影响0行并记录告警。

#### 指数退避如何计算，为什么需要封顶？

第N次领取失败后的延迟为`5 * 2^(N-1)`秒，得到5、10、20、40、80、160秒，之后封顶300秒。指数退避减少Broker故障期间的持续压力；封顶避免故障恢复后单条事件等待过久。领取次数很大时计算器提前返回上限，避免数值溢出。

#### 为什么网络发送和结果写库不能放在同一个事务？

RabbitMQ不参与MySQL本地事务，放进同一个`@Transactional`方法也不能形成跨系统原子提交，反而会在等待Confirm期间长期占用数据库连接和行锁。项目使用三个边界：短事务领取、事务外网络发送、短事务记录结果。

#### 消息发送成功但`PUBLISHED`更新失败会发生什么？

事件保留在`SENDING`。超过2分钟租约后恢复为`PENDING`并再次发布，因此消费者可能收到重复消息。这是“至少一次”可靠投递的典型窗口：宁可重复，也不能因为结果未知而把事件当成功删除；下一阶段使用消费幂等消除重复业务效果。

#### 为什么调度器使用fixedDelay而不是fixedRate？

fixedDelay从上一轮结束后再等待配置间隔。单批最多20条，每条可能等待最多5秒Confirm，如果使用fixedRate，新一轮可能在旧一轮未结束时不断触发并形成重叠压力。fixedDelay使同一调度线程自然串行，跨实例并行仍由数据库锁安全支持。

#### 为什么MySQL JSON字段查询后的字符串顺序可能变化？

MySQL以JSON类型解析和规范化内容，不承诺保留输入时的空格和对象键顺序。`{"eventId":"...","taskId":1}`查询后可能变成`{"taskId": 1, "eventId": "..."}`，二者语义相同。测试应解析成JSON树比较结构，不应比较原始字符串格式。

## 2026-07-19：RabbitMQ消费者、手动ACK与消费幂等

### 本步完成内容

- 使用`@RabbitListener`监听主执行队列，由监听线程同步调用Java Worker；
- 校验JSON、事件类型、schema版本、必填字段及AMQP属性/Header一致性；
- 按`READY_TO_EXECUTE/ALREADY_HANDLED/STALE_ATTEMPT/FUTURE_ATTEMPT/TASK_NOT_FOUND`分类消息；
- 首次消息把`PENDING`条件更新为`QUEUED`，人工重试消息直接使用现有`QUEUED`；
- 抢占SQL同时校验任务ID、`QUEUED`和`retry_count = attemptNo - 1`；
- 成功、明确失败、取消、重复和旧轮次消息ACK；非法消息Reject；临时异常NACK并重新入队；
- `dispatch-mode=mysql/rabbitmq`保证旧扫描调度器和RabbitMQ消费者互斥；
- 真实MySQL和RabbitMQ链路及全量71项测试通过。

### 面试可能追问

- 为什么RabbitMQ监听线程要同步调用Worker，而不是ACK后再提交本地线程池？
- 为什么消息准备判断和最终抢占SQL需要两层幂等？
- `attemptNo`为什么必须等于`retry_count + 1`？
- Worker运行失败为什么仍然ACK消息？
- ACK发送失败会不会导致任务重复执行？
- `basicReject(false)`和`basicNack(false, true)`分别用于什么场景？
- 为什么阶段8.5的NACK重新入队只是过渡方案？
- `SKIP LOCKED`返回空批次是否说明消息丢失？

#### 为什么RabbitMQ监听线程要同步调用Worker，而不是ACK后再提交本地线程池？

ACK代表应用已经完成这条消息。如果先ACK再把任务放入本地内存队列，进程可能在Worker执行前宕机，此时Broker已经删除消息，本地任务也丢失。同步调用让消息在Worker完成数据库状态和结果落库前保持未确认；进程异常退出时RabbitMQ可以重新投递。

#### 为什么消息准备判断和最终抢占SQL需要两层幂等？

准备服务负责解释业务状态，识别旧轮次、未来轮次、取消和终态，并把首次`PENDING`推进为`QUEUED`。但准备结束到Worker抢占之间仍有并发窗口，所以最终必须由一条条件UPDATE原子匹配任务ID、状态和轮次。前一层提供清晰决策，后一层提供并发正确性。

#### `attemptNo`为什么必须等于`retry_count + 1`？

`retry_count`表示已经发起过多少次人工业务重试，首次执行时为0，因此期望尝试号为1；第一次重试后为1，因此期望尝试号为2。把两者绑定后，尝试1的延迟旧消息无法抢占尝试2的任务。

#### Worker运行失败为什么仍然ACK消息？

如果Worker已经把任务和执行记录可靠更新为`FAILED`，消息处理本身已经完成，只是业务结果为失败。再次投递相同消息不会形成正确的新业务尝试；需要重试时应由用户调用重试API，增加`retry_count`并产生新的Outbox事件和`attemptNo`。

#### ACK发送失败会不会导致任务重复执行？

消息可能被重新投递，但不会形成第二次有效执行。准备服务会看到任务已经是`RUNNING/SUCCEEDED/FAILED/CANCELLED`并直接ACK；极端并发下，严格条件UPDATE和`task_execution(task_id, attempt_no)`唯一约束仍会阻止重复抢占和重复执行记录。

#### `basicReject(false)`和`basicNack(false, true)`分别用于什么场景？

`basicReject(deliveryTag, false)`拒绝当前消息且不重新入队，适用于JSON非法、不支持版本、任务不存在或未来轮次等重试也无法自行修复的问题。`basicNack(deliveryTag, false, true)`否定当前消息并重新入队，阶段8.5用于数据库连接等未预期临时异常。

#### 为什么阶段8.5的NACK重新入队只是过渡方案？

持续故障时立即重新入队可能形成高频无限循环，反复占用消费者和Broker。阶段8.6会把它替换为带次数上限和TTL延迟的重试队列，超过上限后进入最终死信队列，做到可恢复、可观测且不形成重试风暴。

#### `SKIP LOCKED`返回空批次是否说明消息丢失？

不是。它只表示当前查询遇到其他事务持有的锁并选择跳过，某个发布器在这一轮可能暂时看不到可领取事件。事件仍在MySQL中，其他发布器提交后，下一次定时扫描可以继续领取。正确测试应验证最终全部事件被互斥领取，而不是强制每个发布器第一次查询都必须拿满一批。

## 2026-07-19：有限重试、TTL回流与最终死信

### 本步完成内容

- 把阶段8.5的立即`NACK + requeue`替换为10秒TTL延迟重试；
- 总处理次数限制为3次，首次无Header按第1次处理；
- 使用自定义Header记录次数、最后错误、失败时间和最终失败标志；
- 重试和死信转发均等待Publisher Confirm并检查mandatory Return；
- 转发成功才ACK原消息，转发失败则NACK并保留原消息；
- 第3次临时失败和永久错误进入`simulation.task.dead.queue`；
- 重试耗尽只条件更新对应轮次且仍待执行的任务为`FAILED`；
- 真实RabbitMQ验证TTL到期自动回主队列，全量88项测试通过。

### 面试可能追问

- 为什么不能继续使用`basicNack(requeue=true)`立即重试？
- `max-delivery-attempts=3`包含首次消费吗？
- 为什么用`x-delivery-attempt`而不直接用RabbitMQ的`x-death`计数？
- 为什么转发到重试队列后不能立刻假设成功并ACK？
- 重试消息发布成功但ACK原消息前宕机会发生什么？
- 为什么业务执行失败不进入消息重试，而基础设施异常需要进入？
- 重试耗尽为什么只更新`PENDING/QUEUED`，不直接更新`RUNNING`？
- 为什么最终死信不自动回到主队列？

#### 为什么不能继续使用`basicNack(requeue=true)`立即重试？

立即重新入队没有延迟和次数上限。数据库或网络持续故障时，同一消息会被消费者高速取出、失败、重新入队，形成重试风暴。TTL重试队列让消息等待10秒后再回主队列，并在第3次失败后进入最终死信，使压力可控且问题可观察。

#### `max-delivery-attempts=3`包含首次消费吗？

包含。首次Outbox消息没有`x-delivery-attempt`时按1处理；第一次延迟重试写2；第二次延迟重试写3。处理次数为3时仍遇到临时异常，不再产生第4次自动重试，而是进入最终死信。

#### 为什么用`x-delivery-attempt`而不直接用RabbitMQ的`x-death`计数？

`x-death`由Broker在死信过程中维护，包含队列、原因、交换机和次数等嵌套历史，结构会受拓扑影响。本项目用简单整数Header表达应用层总处理次数，规则清晰且容易测试；`x-death`仍保留用于诊断TTL回流历史。

#### 为什么转发到重试队列后不能立刻假设成功并ACK？

发送调用返回不代表Broker已经持久化并成功路由。消费者必须等待Publisher Confirm，并确认mandatory消息没有被Return。只有“Confirm ACK且无Return”时才能ACK原消息；否则原消息需要保留，避免重试消息和原消息同时丢失。

#### 重试消息发布成功但ACK原消息前宕机会发生什么？

原消息会重新投递，同时重试队列中已经存在新消息，因此可能重复。这是两个Broker操作之间无法消除的At-Least-Once窗口。任务状态条件、`attemptNo`和执行记录唯一约束负责吸收重复，项目不宣称端到端Exactly Once。

#### 为什么业务执行失败不进入消息重试，而基础设施异常需要进入？

Worker已经把`FAILED`可靠写入任务和执行记录时，消息处理已经完成，只是业务结果失败；新的业务尝试必须由用户重试API产生新`attemptNo`。数据库连接中断等基础设施异常意味着消息尚未得到确定处理结果，适合有限自动重试。

#### 重试耗尽为什么只更新`PENDING/QUEUED`，不直接更新`RUNNING`？

`RUNNING`可能仍有Worker执行或只是心跳暂时延迟，消息消费者不能仅凭一次转发失败就强行覆盖。项目只对相同业务轮次且尚未开始的任务标记失败；`RUNNING`继续交给带心跳时间条件的超时恢复器处理。

#### 为什么最终死信不自动回到主队列？

最终死信通常代表永久契约错误或多次临时故障仍未恢复。自动回流会绕过次数上限并重新形成无限循环。人工处理必须先查看失败Header、修复根因、核对MySQL任务状态和轮次，再决定是否重新发布。

## 2026-07-19：生产默认分发模式切换为RabbitMQ

### 本步完成内容

- 将`application.yml`中的默认值由`mysql`切换为`rabbitmq`；
- 未设置`SIMULATION_DISPATCH_MODE`时，应用默认启用Outbox发布器和RabbitMQ消费者；
- 仍可通过`SIMULATION_DISPATCH_MODE=mysql`显式启用阶段7数据库扫描调度器；
- 测试配置继续使用`simulation.execution.enabled=false`并固定`dispatch-mode=mysql`，避免普通测试无意启动后台消费者或依赖RabbitMQ；
- 两种模式仍由Spring条件装配保证互斥，心跳超时恢复器不受模式切换影响。

### 面试可能追问

- 为什么完成有限重试和死信闭环后才把RabbitMQ设为默认模式？
- 为什么生产默认值和测试默认值可以不同？
- 环境变量`SIMULATION_DISPATCH_MODE`为什么能够覆盖YAML中的默认值？
- 如何证明切换模式后MySQL扫描器和RabbitMQ消费者不会同时执行任务？

#### 为什么完成有限重试和死信闭环后才切换默认模式？

在消费者只有立即重新入队能力时，持续故障可能形成无限高速重试。阶段8.6加入处理次数上限、TTL延迟重试、最终死信、可靠转发和失败状态闭环后，RabbitMQ链路才具备可控失败语义，因此此时切换默认值不会把未完成的消息实验链路直接变成日常运行路径。

#### 为什么测试环境仍固定为MySQL模式？

生产默认值表达系统的正常运行架构；普通单元测试和多数数据库测试则应尽量减少外部依赖与后台线程干扰。RabbitMQ专项集成测试会显式指定`dispatch-mode=rabbitmq`并验证真实Broker，因此测试默认使用MySQL模式不会削弱消息链路覆盖，反而让测试边界更清楚、更稳定。
