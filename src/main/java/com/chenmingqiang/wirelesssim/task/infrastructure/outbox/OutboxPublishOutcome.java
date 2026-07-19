package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

/**
 * 单条Outbox消息发送到RabbitMQ后的判定结果。
 *
 * <p>本枚举只描述生产者到Broker这一段，不代表消费者已经处理消息。</p>
 */
public enum OutboxPublishOutcome {
    /** Broker确认接收，且消息成功路由到至少一个队列。 */
    ACK,
    /** Broker接收了消息，但mandatory机制发现没有队列可以路由。 */
    RETURNED,
    /** Broker通过Publisher Confirm明确拒绝了消息。 */
    NACK,
    /** 在配置的时间内没有收到Publisher Confirm。 */
    TIMEOUT,
    /** 发送调用本身发生连接、序列化或其他即时异常。 */
    SEND_FAILED
}
