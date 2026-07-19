package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import java.time.Instant;
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

/** 把消费失败的原消息可靠转发到TTL重试队列或最终死信队列。 */
@Component
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "dispatch-mode",
        havingValue = "rabbitmq"
)
public class TaskMessageForwarder {

    /** 最后一次失败原因Header。 */
    public static final String LAST_ERROR_HEADER = "x-last-error";
    /** 最后一次失败UTC时间Header。 */
    public static final String LAST_FAILED_AT_HEADER = "x-last-failed-at";
    /** 标记消息已经进入最终死信。 */
    public static final String FINAL_FAILURE_HEADER = "x-final-failure";
    /** 避免把无限长异常文本塞入RabbitMQ Header。 */
    private static final int MAX_ERROR_LENGTH = 1000;

    /** Spring AMQP发送模板，已启用mandatory、Confirm和Return。 */
    private final RabbitTemplate rabbitTemplate;
    /** 消息重试次数与转发Confirm超时配置。 */
    private final SimulationMessagingProperties properties;

    /** 通过构造器注入发送模板和配置。 */
    public TaskMessageForwarder(RabbitTemplate rabbitTemplate, SimulationMessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /** 复制原消息，写入下一次处理次数并可靠发布到TTL重试交换机。 */
    public TaskMessageForwardResult forwardToRetry(Message original, int nextAttempt, String error) {
        if (nextAttempt < 2) {
            throw new IllegalArgumentException("重试消息的nextAttempt必须大于等于2");
        }
        Message forwarded = copyForForward(original, nextAttempt, error, false);
        return publish(
                forwarded,
                SimulationRabbitNames.RETRY_EXCHANGE,
                SimulationRabbitNames.RETRY_ROUTING_KEY,
                "retry"
        );
    }

    /** 复制原消息并可靠发布到最终死信交换机，不再自动返回主队列。 */
    public TaskMessageForwardResult forwardToDeadLetter(Message original, int attempt, String error) {
        Message forwarded = copyForForward(original, Math.max(1, attempt), error, true);
        return publish(
                forwarded,
                SimulationRabbitNames.DEAD_LETTER_EXCHANGE,
                SimulationRabbitNames.DEAD_LETTER_ROUTING_KEY,
                "dead"
        );
    }

    /** 复制必要消息属性和全部业务Header，不复制deliveryTag等消费端瞬时属性。 */
    private Message copyForForward(Message original, int attempt, String error, boolean finalFailure) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties target = new MessageProperties();
        target.setContentType(source.getContentType());
        target.setContentEncoding(source.getContentEncoding());
        target.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        target.setMessageId(source.getMessageId());
        target.setCorrelationId(source.getCorrelationId());
        target.setType(source.getType());
        target.setPriority(source.getPriority());
        target.setTimestamp(source.getTimestamp() == null ? new Date() : source.getTimestamp());
        source.getHeaders().forEach(target::setHeader);
        target.setHeader(TaskMessageDeliveryAttemptResolver.DELIVERY_ATTEMPT_HEADER, attempt);
        target.setHeader(LAST_ERROR_HEADER, truncate(error));
        target.setHeader(LAST_FAILED_AT_HEADER, Instant.now().toString());
        target.setHeader(FINAL_FAILURE_HEADER, finalFailure);
        target.setContentLength(original.getBody().length);
        return new Message(original.getBody(), target);
    }

    /** 等待Confirm并优先检查mandatory Return，只有ACK且无Return才成功。 */
    private TaskMessageForwardResult publish(
            Message message,
            String exchange,
            String routingKey,
            String destination
    ) {
        String messageId = message.getMessageProperties().getMessageId();
        String correlationId = (messageId == null ? "unknown" : messageId)
                + ":" + destination + ":"
                + message.getMessageProperties().getHeader(
                        TaskMessageDeliveryAttemptResolver.DELIVERY_ATTEMPT_HEADER);
        CorrelationData correlationData = new CorrelationData(correlationId);
        try {
            rabbitTemplate.send(exchange, routingKey, message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture().get(
                    properties.forwardConfirmTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                return TaskMessageForwardResult.failure(
                        TaskMessageForwardOutcome.RETURNED,
                        "转发消息不可路由：replyCode=" + returned.getReplyCode()
                                + ", replyText=" + returned.getReplyText()
                );
            }
            if (!confirm.ack()) {
                return TaskMessageForwardResult.failure(
                        TaskMessageForwardOutcome.NACK,
                        "Broker NACK：" + safe(confirm.reason(), "未提供原因")
                );
            }
            return TaskMessageForwardResult.ack();
        } catch (TimeoutException exception) {
            return TaskMessageForwardResult.failure(
                    TaskMessageForwardOutcome.TIMEOUT,
                    "等待转发Publisher Confirm超过" + properties.forwardConfirmTimeout()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return TaskMessageForwardResult.failure(
                    TaskMessageForwardOutcome.SEND_FAILED,
                    "等待转发Publisher Confirm时线程被中断"
            );
        } catch (ExecutionException exception) {
            return TaskMessageForwardResult.failure(
                    TaskMessageForwardOutcome.SEND_FAILED,
                    "转发Publisher Confirm异步失败：" + exceptionDetail(exception.getCause())
            );
        } catch (AmqpException exception) {
            return TaskMessageForwardResult.failure(
                    TaskMessageForwardOutcome.SEND_FAILED,
                    "RabbitMQ转发失败：" + exceptionDetail(exception)
            );
        }
    }

    /** 截断并规范化异常摘要。 */
    private String truncate(String error) {
        String normalized = safe(error, "未提供失败原因");
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    /** 空白文本使用回退值。 */
    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** 生成简短异常说明，不把完整堆栈塞入Header。 */
    private String exceptionDetail(Throwable throwable) {
        if (throwable == null) {
            return "未知异常";
        }
        return throwable.getClass().getSimpleName() + ": " + safe(throwable.getMessage(), "未提供原因");
    }
}
