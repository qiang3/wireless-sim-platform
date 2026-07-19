package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import java.time.Instant;

/**
 * 请求Worker执行某次仿真任务尝试的版本化轻量消息。
 *
 * @param eventId 全局事件ID，同时作为RabbitMQ messageId
 * @param taskId MySQL中的任务ID，消费者据此读取完整任务快照
 * @param attemptNo 业务执行尝试号，用于阻止旧消息抢占后续重试
 * @param eventType 事件类型，当前固定为TASK_EXECUTION_REQUESTED
 * @param schemaVersion 消息结构版本，当前为1
 * @param occurredAt 业务事件产生的UTC时间
 */
public record TaskExecutionRequestedMessage(
        String eventId,
        Long taskId,
        Integer attemptNo,
        String eventType,
        Integer schemaVersion,
        Instant occurredAt
) {
}
