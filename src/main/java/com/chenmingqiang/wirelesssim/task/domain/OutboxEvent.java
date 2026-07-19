package com.chenmingqiang.wirelesssim.task.domain;

import java.time.LocalDateTime;

/**
 * Transactional Outbox事件的持久化对象。
 *
 * <p>任务提交事务先把该对象写入MySQL。后续发布器读取它并发送RabbitMQ，
 * 从而避免业务任务已经创建但消息因进程退出而永久丢失。</p>
 */
public class OutboxEvent {

    /** MySQL内部自增主键，用于高效分页、排序和批量领取。 */
    private Long id;
    /** 跨数据库、RabbitMQ和日志使用的全局UUID事件标识。 */
    private String eventId;
    /** 事件所属聚合类型，当前固定使用EXPERIMENT_TASK。 */
    private String aggregateType;
    /** 事件所属任务ID，当前对应experiment_task.id。 */
    private Long aggregateId;
    /** 事件类型，当前第一版使用TASK_EXECUTION_REQUESTED。 */
    private String eventType;
    /** 业务执行尝试号：首次执行为1，每次人工重试递增。 */
    private Integer attemptNo;
    /** JSON消息结构版本，消费者据此判断能否解析消息。 */
    private Integer schemaVersion;
    /** 最终发送给RabbitMQ的轻量JSON消息正文。 */
    private String payloadJson;
    /** 映射后的RabbitMQ消息优先级，范围为0到5。 */
    private Integer priority;
    /** 当前发布状态，只表示生产者到Broker这一段链路。 */
    private OutboxStatus status;
    /** 已经尝试发布到RabbitMQ的次数。 */
    private Integer publishAttempts;
    /** 发布器下一次允许尝试发送该事件的时间。 */
    private LocalDateTime nextAttemptAt;
    /** 当前领取该事件的发布器实例标识。 */
    private String claimedBy;
    /** 当前发布器领取事件的时间，用于识别领取超时。 */
    private LocalDateTime claimedAt;
    /** 最近一次发布失败的错误摘要。 */
    private String lastError;
    /** 业务事件在任务事务中实际发生的时间。 */
    private LocalDateTime occurredAt;
    /** RabbitMQ确认接管且消息可路由后的发布时间。 */
    private LocalDateTime publishedAt;
    /** Outbox记录创建时间。 */
    private LocalDateTime createdAt;
    /** Outbox记录最后更新时间。 */
    private LocalDateTime updatedAt;

    /** 返回数据库内部主键。 */
    public Long getId() { return id; }
    /** 设置数据库内部主键，通常由MyBatis回填。 */
    public void setId(Long id) { this.id = id; }
    /** 返回全局事件ID。 */
    public String getEventId() { return eventId; }
    /** 设置全局事件ID。 */
    public void setEventId(String eventId) { this.eventId = eventId; }
    /** 返回聚合类型。 */
    public String getAggregateType() { return aggregateType; }
    /** 设置聚合类型。 */
    public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
    /** 返回聚合ID。 */
    public Long getAggregateId() { return aggregateId; }
    /** 设置聚合ID。 */
    public void setAggregateId(Long aggregateId) { this.aggregateId = aggregateId; }
    /** 返回事件类型。 */
    public String getEventType() { return eventType; }
    /** 设置事件类型。 */
    public void setEventType(String eventType) { this.eventType = eventType; }
    /** 返回业务执行尝试号。 */
    public Integer getAttemptNo() { return attemptNo; }
    /** 设置业务执行尝试号。 */
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    /** 返回消息结构版本。 */
    public Integer getSchemaVersion() { return schemaVersion; }
    /** 设置消息结构版本。 */
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
    /** 返回JSON消息正文。 */
    public String getPayloadJson() { return payloadJson; }
    /** 设置JSON消息正文。 */
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    /** 返回消息优先级。 */
    public Integer getPriority() { return priority; }
    /** 设置消息优先级。 */
    public void setPriority(Integer priority) { this.priority = priority; }
    /** 返回发布状态。 */
    public OutboxStatus getStatus() { return status; }
    /** 设置发布状态，查询映射时由MyBatis调用。 */
    public void setStatus(OutboxStatus status) { this.status = status; }
    /** 返回发布尝试次数。 */
    public Integer getPublishAttempts() { return publishAttempts; }
    /** 设置发布尝试次数。 */
    public void setPublishAttempts(Integer publishAttempts) { this.publishAttempts = publishAttempts; }
    /** 返回下一次允许发布时间。 */
    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    /** 设置下一次允许发布时间。 */
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    /** 返回领取者实例标识。 */
    public String getClaimedBy() { return claimedBy; }
    /** 设置领取者实例标识。 */
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    /** 返回事件领取时间。 */
    public LocalDateTime getClaimedAt() { return claimedAt; }
    /** 设置事件领取时间。 */
    public void setClaimedAt(LocalDateTime claimedAt) { this.claimedAt = claimedAt; }
    /** 返回最近一次发布错误。 */
    public String getLastError() { return lastError; }
    /** 设置最近一次发布错误。 */
    public void setLastError(String lastError) { this.lastError = lastError; }
    /** 返回业务事件发生时间。 */
    public LocalDateTime getOccurredAt() { return occurredAt; }
    /** 设置业务事件发生时间。 */
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    /** 返回Broker确认成功时间。 */
    public LocalDateTime getPublishedAt() { return publishedAt; }
    /** 设置Broker确认成功时间。 */
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    /** 返回记录创建时间。 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** 设置记录创建时间。 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    /** 返回记录最后更新时间。 */
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    /** 设置记录最后更新时间。 */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
