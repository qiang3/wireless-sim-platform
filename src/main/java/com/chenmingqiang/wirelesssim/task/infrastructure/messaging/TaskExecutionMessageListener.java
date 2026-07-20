package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import com.chenmingqiang.wirelesssim.task.application.TaskMessagePreparationOutcome;
import com.chenmingqiang.wirelesssim.task.application.TaskMessagePreparationResult;
import com.chenmingqiang.wirelesssim.task.application.TaskMessagePreparationService;
import com.chenmingqiang.wirelesssim.task.application.TaskMessageFailureService;
import com.chenmingqiang.wirelesssim.task.infrastructure.execution.SimulationTaskWorker;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** RabbitMQ任务消费者：同步执行Worker，并在数据库处理完成后手动确认消息。 */
@Component
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "enabled",
        havingValue = "true"
)
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "dispatch-mode",
        havingValue = "rabbitmq"
)
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "worker-mode",
        havingValue = "java-mock",
        matchIfMissing = true
)
@ConditionalOnProperty(
        prefix = "simulation.messaging",
        name = "consumer-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TaskExecutionMessageListener {

    /** 输出消费结论和异常原因，便于关联RabbitMQ与数据库状态。 */
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionMessageListener.class);

    /** 校验JSON载荷、消息版本及AMQP属性。 */
    private final TaskExecutionMessageValidator validator;
    /** 读取MySQL最终事实并准备PENDING任务。 */
    private final TaskMessagePreparationService preparationService;
    /** 复用阶段7的Java模拟执行和结果闭环。 */
    private final SimulationTaskWorker worker;
    /** 读取当前消息是第几次处理；首次无Header时返回1。 */
    private final TaskMessageDeliveryAttemptResolver attemptResolver;
    /** 可靠发布到TTL重试交换机或最终死信交换机。 */
    private final TaskMessageForwarder forwarder;
    /** 重试耗尽后条件同步尚未执行任务的FAILED状态。 */
    private final TaskMessageFailureService failureService;
    /** 提供最大消息处理次数。 */
    private final SimulationMessagingProperties messagingProperties;

    /** Spring通过构造器注入消费者所需的三个协作者。 */
    public TaskExecutionMessageListener(
            TaskExecutionMessageValidator validator,
            TaskMessagePreparationService preparationService,
            SimulationTaskWorker worker,
            TaskMessageDeliveryAttemptResolver attemptResolver,
            TaskMessageForwarder forwarder,
            TaskMessageFailureService failureService,
            SimulationMessagingProperties messagingProperties
    ) {
        this.validator = validator;
        this.preparationService = preparationService;
        this.worker = worker;
        this.attemptResolver = attemptResolver;
        this.forwarder = forwarder;
        this.failureService = failureService;
        this.messagingProperties = messagingProperties;
    }

    /**
     * 同步消费一条执行请求。
     * 方法返回前Worker已经成功结束、明确失败、取消，或被数据库幂等规则吸收。
     */
    @RabbitListener(queues = SimulationRabbitNames.EXECUTE_QUEUE)
    public void onMessage(Message rawMessage, Channel channel) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        int deliveryAttempt = 1;
        TaskExecutionRequestedMessage parsedMessage = null;
        try {
            deliveryAttempt = attemptResolver.resolve(rawMessage);
            parsedMessage = validator.parseAndValidate(rawMessage);
            TaskMessagePreparationResult preparation = preparationService.prepare(
                    parsedMessage.taskId(),
                    parsedMessage.attemptNo()
            );
            handlePreparedMessage(rawMessage, parsedMessage, preparation, channel, deliveryTag, deliveryAttempt);
        } catch (InvalidTaskMessageException exception) {
            log.warn("永久非法消息进入最终死信：deliveryTag={}, reason={}", deliveryTag, exception.getMessage());
            forwardPermanently(rawMessage, deliveryAttempt, exception.getMessage(), channel, deliveryTag);
        } catch (RuntimeException exception) {
            log.error("消费任务消息发生临时异常：deliveryTag={}, attempt={}",
                    deliveryTag, deliveryAttempt, exception);
            try {
                forwardTransiently(
                        rawMessage,
                        parsedMessage,
                        deliveryAttempt,
                        errorSummary(exception),
                        channel,
                        deliveryTag
                );
            } catch (RuntimeException forwardingException) {
                // 死信已发布但数据库状态更新失败时也可能进入这里；保留原消息优先于静默丢失。
                log.error("处理重试/死信转发结果失败，原消息重新入队：deliveryTag={}",
                        deliveryTag, forwardingException);
                channel.basicNack(deliveryTag, false, true);
            }
        }
    }

    /** 根据准备结果选择同步执行、直接ACK或永久拒绝。 */
    private void handlePreparedMessage(
            Message rawMessage,
            TaskExecutionRequestedMessage message,
            TaskMessagePreparationResult preparation,
            Channel channel,
            long deliveryTag,
            int deliveryAttempt
    ) throws IOException {
        TaskMessagePreparationOutcome outcome = preparation.outcome();
        switch (outcome) {
            case READY_TO_EXECUTE -> {
                // 返回true表示成功；返回false也可能是业务失败、取消或并发未抢到，均已形成确定结论。
                worker.execute(message.taskId(), message.attemptNo());
                channel.basicAck(deliveryTag, false);
            }
            case ALREADY_HANDLED, STALE_ATTEMPT -> channel.basicAck(deliveryTag, false);
            case FUTURE_ATTEMPT, TASK_NOT_FOUND -> forwardPermanently(
                    rawMessage,
                    deliveryAttempt,
                    preparation.detail(),
                    channel,
                    deliveryTag
            );
        }
        log.info(
                "任务消息处理完成：eventId={}, taskId={}, attemptNo={}, outcome={}, detail={}",
                message.eventId(),
                message.taskId(),
                message.attemptNo(),
                outcome,
                preparation.detail()
        );
    }

    /** 临时异常未达到上限时延迟重试，达到上限时进入最终死信。 */
    private void forwardTransiently(
            Message rawMessage,
            TaskExecutionRequestedMessage parsedMessage,
            int currentAttempt,
            String error,
            Channel channel,
            long deliveryTag
    ) throws IOException {
        if (currentAttempt < messagingProperties.maxDeliveryAttempts()) {
            TaskMessageForwardResult result = forwarder.forwardToRetry(
                    rawMessage,
                    currentAttempt + 1,
                    error
            );
            acknowledgeOriginalAfterForward(result, channel, deliveryTag, "重试");
            return;
        }

        TaskMessageForwardResult result = forwarder.forwardToDeadLetter(rawMessage, currentAttempt, error);
        if (!result.isSuccess()) {
            log.error("最终死信发布失败，原消息重新入队：deliveryTag={}, outcome={}, detail={}",
                    deliveryTag, result.outcome(), result.detail());
            channel.basicNack(deliveryTag, false, true);
            return;
        }
        if (parsedMessage != null) {
            boolean marked = failureService.markDeliveryExhausted(
                    parsedMessage.taskId(),
                    parsedMessage.attemptNo(),
                    error
            );
            log.info("消息重试耗尽状态同步：taskId={}, attemptNo={}, updated={}",
                    parsedMessage.taskId(), parsedMessage.attemptNo(), marked);
        }
        channel.basicAck(deliveryTag, false);
    }

    /** 永久异常不进入重试队列，可靠发布到最终死信后再ACK原消息。 */
    private void forwardPermanently(
            Message rawMessage,
            int currentAttempt,
            String error,
            Channel channel,
            long deliveryTag
    ) throws IOException {
        TaskMessageForwardResult result = forwarder.forwardToDeadLetter(rawMessage, currentAttempt, error);
        acknowledgeOriginalAfterForward(result, channel, deliveryTag, "最终死信");
    }

    /** 转发成功才ACK原消息；转发结果不确定或失败时保留原消息重新投递。 */
    private void acknowledgeOriginalAfterForward(
            TaskMessageForwardResult result,
            Channel channel,
            long deliveryTag,
            String destination
    ) throws IOException {
        if (result.isSuccess()) {
            channel.basicAck(deliveryTag, false);
            return;
        }
        log.error("{}发布失败，原消息重新入队：deliveryTag={}, outcome={}, detail={}",
                destination, deliveryTag, result.outcome(), result.detail());
        channel.basicNack(deliveryTag, false, true);
    }

    /** 把异常压缩为Header和数据库错误字段可保存的摘要。 */
    private String errorSummary(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
