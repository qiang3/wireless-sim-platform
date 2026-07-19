package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

/** 消费者把原消息可靠转发到重试或最终死信交换机后的结果。 */
public enum TaskMessageForwardOutcome {
    /** Broker确认接收，且消息成功路由。 */
    ACK,
    /** Broker接收消息，但mandatory检查发现没有可路由队列。 */
    RETURNED,
    /** Broker明确拒绝消息。 */
    NACK,
    /** 等待Publisher Confirm超时，结果未知。 */
    TIMEOUT,
    /** 发送调用、连接或异步确认发生异常。 */
    SEND_FAILED
}
