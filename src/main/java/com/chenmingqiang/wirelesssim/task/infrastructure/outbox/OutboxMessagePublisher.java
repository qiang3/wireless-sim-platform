package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 把单条Outbox事件可靠发送到RabbitMQ，并等待Publisher Confirm与Return结果。
 *
 * <p>本类只负责网络发布和结果判定，不修改Outbox数据库状态。
 * 第3步会根据返回结果在独立事务中写入PUBLISHED或失败重试信息。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "dispatch-mode",
        havingValue = "rabbitmq"
)
public class OutboxMessagePublisher {

    /** Spring AMQP发送模板，application.yml已启用correlated confirm、return和mandatory。 */
    private final RabbitTemplate rabbitTemplate;
    /** Outbox配置，当前使用其中的Confirm等待超时。 */
    private final OutboxPublisherProperties properties;

    /** 由Spring通过构造器注入RabbitTemplate和类型安全配置。 */
    public OutboxMessagePublisher(
            RabbitTemplate rabbitTemplate,
            OutboxPublisherProperties properties
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * 将事件发送到正式任务交换机和执行路由键。
     *
     * @param event 已经由当前发布器领取并标记为SENDING的Outbox事件
     * @return ACK、Return、NACK、超时或立即失败的结构化结果
     */
    public OutboxPublishResult publish(OutboxEvent event) {
        validateEvent(event);
        return publishTo(
                event,
                SimulationRabbitNames.TASK_EXCHANGE,
                SimulationRabbitNames.EXECUTE_ROUTING_KEY
        );
    }

    /**
     * 完成实际发送与结果判定。
     *
     * <p>保留包级可见性，单元测试可以模拟不可路由键；生产代码统一调用公开的publish方法。</p>
     */
    OutboxPublishResult publishTo(OutboxEvent event, String exchange, String routingKey) {
        Message message = buildPersistentMessage(event);
        CorrelationData correlationData = new CorrelationData(event.getEventId());

        try {
            rabbitTemplate.send(exchange, routingKey, message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    properties.confirmTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );

            // mandatory Return会在Confirm完成前写入同一个CorrelationData，必须优先判断。
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                return OutboxPublishResult.failure(
                        OutboxPublishOutcome.RETURNED,
                        "消息不可路由：replyCode=" + returned.getReplyCode()
                                + ", replyText=" + returned.getReplyText()
                                + ", exchange=" + returned.getExchange()
                                + ", routingKey=" + returned.getRoutingKey()
                );
            }
            if (!confirm.ack()) {
                return OutboxPublishResult.failure(
                        OutboxPublishOutcome.NACK,
                        "Broker NACK：" + safeDetail(confirm.reason(), "未提供原因")
                );
            }
            return OutboxPublishResult.ack();
        } catch (TimeoutException exception) {
            return OutboxPublishResult.failure(
                    OutboxPublishOutcome.TIMEOUT,
                    "等待Publisher Confirm超过" + properties.confirmTimeout()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OutboxPublishResult.failure(
                    OutboxPublishOutcome.SEND_FAILED,
                    "等待Publisher Confirm时线程被中断"
            );
        } catch (ExecutionException exception) {
            return OutboxPublishResult.failure(
                    OutboxPublishOutcome.SEND_FAILED,
                    "Publisher Confirm异步失败：" + exceptionDetail(exception.getCause())
            );
        } catch (AmqpException exception) {
            return OutboxPublishResult.failure(
                    OutboxPublishOutcome.SEND_FAILED,
                    "RabbitMQ发送失败：" + exceptionDetail(exception)
            );
        }
    }

    /** 把数据库JSON载荷转换为带完整业务属性的持久化AMQP消息。 */
    private Message buildPersistentMessage(OutboxEvent event) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        messageProperties.setMessageId(event.getEventId());
        messageProperties.setCorrelationId(event.getEventId());
        messageProperties.setType(event.getEventType());
        messageProperties.setPriority(event.getPriority());
        messageProperties.setTimestamp(Date.from(event.getOccurredAt().toInstant(ZoneOffset.UTC)));
        messageProperties.setHeader("eventId", event.getEventId());
        messageProperties.setHeader("outboxId", event.getId());
        messageProperties.setHeader("aggregateType", event.getAggregateType());
        messageProperties.setHeader("aggregateId", event.getAggregateId());
        messageProperties.setHeader("attemptNo", event.getAttemptNo());
        messageProperties.setHeader("schemaVersion", event.getSchemaVersion());

        byte[] body = event.getPayloadJson().getBytes(StandardCharsets.UTF_8);
        messageProperties.setContentLength(body.length);
        return new Message(body, messageProperties);
    }

    /** 在访问消息字段前给出清晰错误，避免空指针错误难以定位。 */
    private void validateEvent(OutboxEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("待发布Outbox事件不能为空");
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            throw new IllegalArgumentException("Outbox eventId不能为空");
        }
        if (event.getPayloadJson() == null || event.getPayloadJson().isBlank()) {
            throw new IllegalArgumentException("Outbox消息载荷不能为空");
        }
        if (event.getOccurredAt() == null) {
            throw new IllegalArgumentException("Outbox事件发生时间不能为空");
        }
        if (event.getPriority() == null) {
            throw new IllegalArgumentException("Outbox消息优先级不能为空");
        }
    }

    /** 空白Broker原因使用默认说明，保证错误日志始终可读。 */
    private String safeDetail(String detail, String fallback) {
        return detail == null || detail.isBlank() ? fallback : detail;
    }

    /** 提取异常类型和消息，避免把完整堆栈保存到后续Outbox错误字段。 */
    private String exceptionDetail(Throwable throwable) {
        if (throwable == null) {
            return "未知异常";
        }
        return throwable.getClass().getSimpleName() + ": "
                + safeDetail(throwable.getMessage(), "未提供原因");
    }
}
