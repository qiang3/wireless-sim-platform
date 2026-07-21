# 项目高频面试问答

本文是项目面试速查材料。完整开发过程和更多问题保留在[学习与面试日志](03-learning-log.md)，本文只整理最可能围绕简历项目继续追问的问题。

## 1. 回答方法

每个问题按三层准备：

1. **先说结论**：20秒内直接回答“为什么”和“怎么做”；
2. **再讲项目**：说明本项目采用的具体机制，不泛泛背八股；
3. **最后说边界**：主动区分已经实现、可以扩展和不能夸大的部分。

如果面试官没有继续追问，说完“先说结论”与“项目实现”即可，不要主动展开全部细节。

---

## 2. 架构与数据边界

### Q1：这个项目的整体架构是什么？

**先说结论：** 项目是模块化单体Java后端加独立Python计算进程，不是微服务。Java统一管理业务状态，Python专注GRPO推理，MySQL是最终事实来源，RabbitMQ负责异步传递，Redis负责缓存和限流。

**项目实现：** Java内部按`user`、`scenario`、`task`等业务模块划分，每个模块再分API、应用、领域和基础设施层。Python通过RabbitMQ和三个内部HTTP接口与Java协作，不直接修改业务数据库。

### Q2：为什么不一开始拆成微服务？

**先说结论：** 当前业务规模和开发人数不需要承担服务发现、分布式配置、链路追踪和跨服务事务等成本，模块化单体已经能保证清晰边界。

**项目实现：** 用户、场景和任务在一个Spring Boot进程中，便于使用本地事务维护一致性；只有依赖PyTorch和CUDA的模型计算独立为Python进程。后续如果计算类型和Worker规模增加，可以沿现有消息与HTTP契约拆分。

### Q3：MySQL、RabbitMQ和Redis分别是什么角色？

**先说结论：** MySQL保存不能丢的业务事实；RabbitMQ传递待执行任务；Redis保存可以重建的缓存和限流计数。

**项目实现：** 客户端查询任务时以MySQL状态为准，不能把RabbitMQ队列长度当成业务状态。Redis宕机后任务和结果不会丢失，只会暂时回源MySQL或失去入口限流。

### Q4：为什么Python不直接访问MySQL？

**先说结论：** 如果Java和Python都能修改任务表，一致性规则和事务边界会分散，重复消息下更容易产生冲突。

**项目实现：** Python只负责验证场景、加载权重和计算，通过`claim`、`complete`、`fail`接口回调Java。Java集中执行状态机、唯一约束、结果事务和缓存失效。

---

## 3. 事务与Transactional Outbox

### Q5：`@Transactional`在项目中解决什么问题？

**先说结论：** 它把必须一起成功或一起失败的多次数据库操作放入同一事务，例如任务和Outbox事件同时创建，或者任务、执行记录和结果同时完成。

**项目实现：** `TaskService`提交任务时插入`experiment_task`和`outbox_event`；Worker成功回调时更新任务终态、执行终态并插入唯一结果。任一步抛出未处理运行时异常，整个事务回滚。

**继续追问：** Spring事务通常由代理拦截外部方法调用，同类内部自调用可能绕过代理；事务上下文也不会自动跨越`taskExecutor.execute(...)`切换到另一个线程。

### Q6：什么是Transactional Outbox？

**先说结论：** 先在同一个本地数据库事务中写业务数据和待发送事件，再由后台发布器把事件发送到消息队列。

**项目实现：** 任务创建成功时必然存在对应Outbox事件。即使RabbitMQ暂时不可用或Java在提交后宕机，事件仍留在MySQL中，可以被后续扫描并重发，从而消除“任务已受理但消息永久丢失”的窗口。

### Q7：为什么不能在任务事务中直接发送RabbitMQ？

**先说结论：** MySQL提交和RabbitMQ发布属于两个独立系统，没有共同的本地事务，任意先后顺序都有中间失败窗口。

**项目实现：** 如果先提交数据库再发消息，进程可能在发送前宕机；如果先发消息再提交数据库，Worker可能看见一条尚不存在或最终回滚的任务。Outbox把跨系统问题转换成“数据库本地事务加可重试发送”。

### Q8：为什么`experiment_task`和`outbox_event`不合并成一张表？

**先说结论：** 业务任务描述用户要做什么，Outbox事件描述某次业务变化需要发送什么消息，两者生命周期和数量关系不同。

**项目实现：** 任务有业务状态、场景快照和重试次数；Outbox有发布状态、领取者、租约、发送次数和下次重试时间。分表可以独立扩展发送策略，也支持同一业务对象未来产生多类事件。

### Q9：用了Outbox为什么仍然需要扫描数据库？

**先说结论：** Outbox只保证事件可靠地留在数据库，仍需要发布器发现尚未发送的事件并投递到RabbitMQ。

**项目实现：** 调度器只扫描小范围的`PENDING`或租约过期事件，不再像早期MySQL模式那样扫描全部待执行业务任务。任务进入RabbitMQ后由Broker主动分发给消费者。

### Q10：`FOR UPDATE SKIP LOCKED`有什么作用？

**先说结论：** `FOR UPDATE`锁住本次领取的记录，`SKIP LOCKED`让其他并发发布器跳过已锁记录，继续领取其他事件，而不是等待。

**项目实现：** 多个发布器可以并行领取不同Outbox事件。领取事务只完成选取和租约更新，不在持锁期间等待RabbitMQ Confirm，避免长事务占锁。

### Q11：为什么还需要`claimed_by`和`claimed_at`？

**先说结论：** 它们组成事件领取租约，用于确认谁拥有当前发送权，并在发布器崩溃后恢复长期卡住的事件。

**项目实现：** 更新发布结果时同时校验事件状态和`claimed_by`，防止旧发布器覆盖新领取者的结果；`claimed_at`超时后事件可以回到待发送状态。

### Q12：消息已经发送成功，但更新Outbox为`PUBLISHED`失败怎么办？

**先说结论：** 该事件会再次发送，因此系统必须允许重复消息，并在消费端保证业务幂等。

**项目实现：** Outbox不能原子地同时提交RabbitMQ和MySQL状态。项目接受这种不确定窗口，通过任务状态、尝试号、条件更新和唯一约束吸收重复业务效果。

---

## 4. RabbitMQ可靠投递与重试

### Q13：Publisher Confirm和Publisher Return有什么区别？

**先说结论：** Confirm说明Broker是否接收了发布；Return用于`mandatory`消息无法路由到任何队列时把消息退回生产者。

**项目实现：** 只有消息获得Confirm ACK且没有Return，才把Outbox标记为`PUBLISHED`。Confirm ACK不代表消费者已经执行业务成功。

### Q14：队列持久化和消息持久化能保证绝不丢消息吗？

**先说结论：** 不能。它们提高Broker重启后的恢复能力，但仍需生产者确认、正确路由、消费者ACK和业务幂等共同构成可靠链路。

**项目实现：** 项目同时使用持久化交换机/队列、持久化消息、Confirm、Return、Outbox重试和消费端手动ACK。

### Q15：为什么消费者必须在业务完成后手动ACK？

**先说结论：** 如果先ACK再执行业务，进程在计算或落库时崩溃，RabbitMQ会认为消息已经完成，不会再次投递。

**项目实现：** Python完成推理并收到Java成功回调后才ACK。若ACK前连接断开，消息会重新投递，数据库幂等规则负责吸收重复领取或重复完成。

### Q16：为什么RabbitMQ不能保证任务只执行一次？

**先说结论：** ACK可能丢失，生产者也可能在已发送但未记录成功时重发，因此可靠消息系统通常允许重复投递。

**项目实现：** 项目的准确口径是“At-Least-Once Delivery + Effectively-Once Business Effect”，即消息可能重复到达，但最终只形成一份有效业务结果。

### Q17：`prefetch=1`有什么作用？

**先说结论：** 每个消费者同一时间最多持有一条未ACK消息，避免秒级模型任务在单个Worker中堆积过多。

**项目实现：** Python Worker一次处理一个GRPO评估任务。未来增加多个Worker进程可以横向消费；若任务变轻或单Worker支持并发，再结合显存和吞吐量测试调整prefetch。

### Q18：重试队列、TTL和DLX如何配合？

**先说结论：** 临时失败消息先转发到带TTL的重试队列，等待时间到期后通过死信交换路由回主队列，从而实现延迟重试。

**项目实现：** 每次转发都会增加投递尝试Header；达到上限后进入最终死信队列，避免无限立即重入主队列造成CPU空转和故障放大。

### Q19：业务重试和消息投递重试有什么区别？

**先说结论：** 消息投递重试处理网络、Java API暂时不可用等基础设施故障；业务重试表示一次计算已明确失败，需要形成新的执行尝试。

**项目实现：** 基础设施异常经过RabbitMQ有限延迟重试；如果业务失败已经被Java可靠记录，原消息可以ACK，由用户或平台触发新的`attemptNo`。

### Q20：为什么重试耗尽后要进入死信，而不是一直重试？

**先说结论：** 永久错误无限重试只会占用队列和计算资源，还会掩盖真实故障。

**项目实现：** 非法消息和超过最大次数的临时失败进入最终死信，并保留错误类型和尝试信息，等待人工分析；死信不会自动回流主队列。

---

## 5. 幂等、状态机与数据库约束

### Q21：HTTP任务提交如何实现幂等？

**先说结论：** 同一用户携带相同`Idempotency-Key`和相同规范化请求时返回原任务，不重复创建；同一键对应不同请求则拒绝。

**项目实现：** 系统同时保存幂等键和`request_hash`，Java先查询提升正常路径体验，MySQL唯一约束`(creator_id, idempotency_key)`处理并发竞争。只在Java层先查重仍存在两个请求同时查不到的竞态。

### Q22：请求摘要为什么需要规范化？

**先说结论：** 语义相同的请求可能因为JSON字段顺序或格式不同产生不同文本，直接对原字符串哈希会误判。

**项目实现：** 先把影响任务语义的字段按固定结构序列化，再计算摘要，用它判断一次幂等重放是否真的是同一请求。

### Q23：消费端如何防止重复结果？

**先说结论：** 通过业务状态判断、尝试号、条件更新以及数据库唯一约束形成多层防线。

**项目实现：** `task_execution(task_id, attempt_no)`保证同一轮只有一条执行记录，`simulation_result(task_id)`保证一个任务只有一份结果。重复`claim`返回可恢复状态，重复`complete`返回已处理，不插入第二份结果。

### Q24：为什么需要`attemptNo`？

**先说结论：** 它区分同一个业务任务的不同执行轮次，防止上一轮延迟消息或回调污染新一轮状态。

**项目实现：** 消息中的`attemptNo`必须与当前任务期望轮次一致；人工重试增加轮次并形成新执行记录，但仍保留原业务任务和场景快照的追踪关系。

### Q25：乐观锁和状态条件更新有什么区别？

**先说结论：** 乐观锁用版本号检测并发修改；状态条件更新直接要求数据库当前状态符合某次合法迁移。两者都把并发判断放进SQL，而不是只在Java内存中判断。

**项目实现：** 场景更新使用`lock_version`防止后写覆盖先写；任务领取、取消和完成使用状态、尝试号等条件更新，受影响行数为0表示状态已经变化或请求过期。

### Q26：为什么任务要保存场景快照？

**先说结论：** 历史任务必须使用提交时的参数，不能随着原场景后续修改或归档而改变。

**项目实现：** 任务同时保存`scenario_id`用于追踪来源，保存不可变快照用于真实执行和复现。Python领取的是任务快照，不是当前场景表内容。

---

## 6. Redis缓存与限流

### Q27：为什么使用Cache Aside？

**先说结论：** 应用先读缓存，未命中再读MySQL并回填；写操作以MySQL事务为准，成功提交后删除缓存。

**项目实现：** Redis只缓存任务详情。缓存不存在、内容损坏或Redis不可用时回源MySQL，因此不会把缓存当作最终事实。

### Q28：为什么必须在事务提交后删除缓存？

**先说结论：** 如果事务尚未提交就删缓存，另一个请求可能立即读取旧数据库值并重新写回缓存；如果事务最终回滚，提前删缓存也产生了不必要的外部副作用。

**项目实现：** `TaskCacheInvalidationService`通过事务同步回调在`afterCommit`删除任务缓存，保证数据库成功后再使缓存失效。

### Q29：为什么限流使用Lua脚本？

**先说结论：** Lua让计数递增、首次设置过期时间和返回判断在Redis中原子执行，避免多个请求分别执行命令产生竞态。

**项目实现：** 项目按用户限制任务提交频率。Lua原子性只解决单个Redis实例内的并发更新，不等同于数据库事务或分布式Exactly Once。

### Q30：Redis不可用为什么选择Fail Open？

**先说结论：** 当前限流是保护性能力，不是资金或权限规则；Redis故障时继续允许请求，可以保证核心业务可用。

**项目实现：** 即使限流暂时失效，JWT权限、参数校验、数据库唯一约束和任务幂等仍然存在。高风险业务可能改成Fail Closed，这取决于业务取舍。

### Q31：为什么不用Redis分布式锁保证任务只执行一次？

**先说结论：** 数据库本来就是任务状态和结果的最终事实来源，条件更新和唯一约束已经能给出持久、可审计的并发裁决。

**项目实现：** Redis锁还要处理过期、续租、误释放和Redis故障，不能替代MySQL事务；项目没有为了技术栈数量额外引入它。

---

## 7. 异步线程与执行状态

### Q32：`taskExecutor.execute(...)`为什么会切换线程？

**先说结论：** 调用线程把一个`Runnable`提交到线程池工作队列后返回，线程池中的Worker线程再取出并调用`run()`，所以实际任务在另一个线程执行。

**项目实现：** Java Mock的调度器使用Lambda把`executeAndRelease(taskId)`封装为`Runnable`提交线程池。Lambda能这样使用，是因为`Runnable`是只有一个抽象方法`run()`的函数式接口。

### Q33：切换线程后原来的事务还在吗？

**先说结论：** 通常不在。Spring事务上下文绑定在线程上，不会自动从提交线程传播到线程池Worker线程。

**项目实现：** 异步线程调用独立Service方法，由这些方法自己开启所需的短事务，而不是试图让提交请求的大事务覆盖整个秒级执行过程。

### Q34：进度和业务心跳为什么同事务更新？

**先说结论：** 进度变化代表Worker仍然存活，如果进度提交而心跳失败，恢复器可能把正在工作的任务误判为失联。

**项目实现：** Java Mock模式中两项更新一起成功或一起回滚。RabbitMQ/Python推理只有几秒，当前简化版本不提供逐回合进度和推理中取消。

### Q35：RabbitMQ心跳和任务业务心跳有什么区别？

**先说结论：** RabbitMQ心跳检测网络连接是否存活；业务心跳记录某次任务执行是否仍有进展，两者层次不同。

**项目实现：** 长时间断点可能阻塞Pika处理协议心跳，Broker关闭连接后再次`close`会触发连接状态异常；这不等同于任务表中的`heartbeat_at`超时。

---

## 8. Java/Python协作、模型与安全

### Q36：`claim`、`complete`和`fail`三个接口分别做什么？

**先说结论：** `claim`领取或恢复执行轮次并返回快照，`complete`原子保存成功结果和终态，`fail`可靠记录分类失败。

**项目实现：** 短接口让Python不持有数据库事务。重复调用会根据当前任务和执行状态返回`RESUMABLE`或`ALREADY_HANDLED`等幂等结果。

### Q37：JWT和Worker Token有什么区别？

**先说结论：** JWT代表具体登录用户，用于外部业务API和资源所有权；Worker Token代表受信任的内部计算进程，不对应某个用户。

**项目实现：** 用户接口使用`Authorization: Bearer <JWT>`；Python内部接口使用独立Worker凭据。当前静态Token适合本地演示，生产环境应升级为轮换密钥、短期服务身份或mTLS。

### Q38：`worker-mode`解决什么问题？

**先说结论：** 它保证Java Mock和Python GRPO只有一个消费者实现生效，避免两个Worker竞争同一业务任务。

**项目实现：** `java-mock`用于无CUDA环境验证工程链路，`python`用于真实预训练模型推理。Mock结果明确标记为非科研结果。

### Q39：如何保证GRPO结果可追踪和复现？

**先说结论：** 固定场景快照、权重文件、模型结构、单位映射和seed，并把这些元数据与结果一起保存。

**项目实现：** 结果记录模型ID、权重SHA-256、基础seed和吞吐量统计；Python使用`eval()`和`torch.inference_mode()`评估。SHA-256证明使用的是哪份权重，但不能单独证明模型正确。

### Q40：为什么当前不实现在线训练、逐回合进度和运行中取消？

**先说结论：** 当前目标是把已有预训练模型接入可靠的秒级评估闭环，训练和长任务控制会显著扩大状态、资源和故障恢复范围。

**项目实现：** 当前只接受3设备RSMA吞吐量场景，AoI为空。若未来加入训练，需要增加训练任务类型、GPU资源调度、检查点存储、指标流、断点续训和更细的取消协议。

---

## 9. 测试与项目可信度

### Q41：项目测试覆盖了什么？

**先说结论：** Java 97项测试覆盖用户、场景、任务、事务、消息、Redis和Worker API；Python 10项测试覆盖契约、单位、模型兼容、可复现性和ACK/重试顺序。

**项目实现：** 除MockMvc接口测试和单元测试外，还使用真实MySQL、Redis和RabbitMQ进行集成测试，并完成真实CUDA、预训练权重和完整任务链路手工验收。

### Q42：MockMvc测试和真实集成测试有什么区别？

**先说结论：** MockMvc在Spring测试上下文中模拟HTTP请求，适合验证路由、安全、参数和响应；真实集成测试还验证SQL、事务、唯一约束和外部基础设施行为。

**项目实现：** 只用MockMvc不能证明`FOR UPDATE SKIP LOCKED`、MySQL事务回滚、RabbitMQ Confirm或Redis Lua在真实组件上正确，因此项目同时保留不同层次的测试。

### Q43：如何证明幂等真的有效？

**先说结论：** 不能只看一次正常请求，要主动制造相同幂等键、并发提交、重复消息、重复领取和重复成功回调。

**项目实现：** 验证最终只有一个业务任务或一份结果，并检查数据库唯一约束、返回状态和四张业务表终态一致。测试既验证正常路径，也验证重复与失败窗口。

### Q44：项目有没有完成性能压测？

**先说结论：** 当前没有形成正式压测报告，因此不能声称支持某个QPS或高并发规模。

**项目实现：** 已完成异步解耦、入口限流、缓存和多发布器领取等性能基础设计，但它们不等于压测结论。简历和面试只陈述这些已实现机制，不给出未经测试的容量数字。

---

## 10. 高频源码入口

| 主题 | 主要入口 |
|---|---|
| 任务提交、快照与HTTP幂等 | [`TaskService.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/application/TaskService.java) |
| Outbox领取与租约 | [`OutboxClaimService.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/application/OutboxClaimService.java)、[`OutboxEventMapper.xml`](../src/main/resources/mapper/task/OutboxEventMapper.xml) |
| RabbitMQ发布与Confirm/Return | [`OutboxMessagePublisher.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/infrastructure/outbox/OutboxMessagePublisher.java) |
| RabbitMQ消费、ACK与转发 | [`TaskExecutionMessageListener.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/infrastructure/messaging/TaskExecutionMessageListener.java) |
| Java Mock异步线程 | [`SimulationTaskDispatcher.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/infrastructure/execution/SimulationTaskDispatcher.java) |
| 执行状态与结果事务 | [`TaskExecutionLifecycleService.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/application/TaskExecutionLifecycleService.java) |
| Redis缓存失效 | [`TaskCacheInvalidationService.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/infrastructure/redis/TaskCacheInvalidationService.java) |
| Redis Lua限流 | [`TaskSubmissionRateLimiter.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/infrastructure/redis/TaskSubmissionRateLimiter.java) |
| Python Worker内部接口 | [`PythonWorkerService.java`](../src/main/java/com/chenmingqiang/wirelesssim/task/application/PythonWorkerService.java) |
| Python消息消费与ACK | [`main.py`](../python-worker/worker/main.py) |
| Java/Python字段和单位契约 | [`contract.py`](../python-worker/worker/contract.py) |
| GRPO确定性评估 | [`evaluate_grpo.py`](../python-worker/eval/evaluate_grpo.py) |
| 数据库唯一约束 | [`V1__init_schema.sql`](../src/main/resources/db/migration/V1__init_schema.sql)、[`V3__create_outbox_event.sql`](../src/main/resources/db/migration/V3__create_outbox_event.sql) |

## 11. 模拟面试练习顺序

第一轮只练以下10题：Q1、Q3、Q6、Q13、Q15、Q16、Q21、Q27、Q32、Q36。

第二轮增加故障窗口：Q7、Q10、Q12、Q18、Q19、Q23、Q28、Q33、Q35、Q43。

第三轮做完整深挖：从源码入口随机选择一个类，先说明它在总链路中的位置，再解释关键方法、事务边界和失败时会发生什么。

验收标准：每道题能先用20秒给出结论；被追问后能结合本项目机制回答1分钟；找得到对应源码入口；不会把预训练推理说成在线训练、把业务幂等说成消息Exactly Once，或在没有数据时声称高并发。
