package com.chenmingqiang.wirelesssim.task.domain;

/**
 * Outbox事件发布状态。
 *
 * <p>该状态只描述“数据库事件到RabbitMQ”的发布进度，不描述仿真任务是否执行成功。</p>
 */
public enum OutboxStatus {
    /** 事件已经可靠写入MySQL，正在等待首次发布或下一次重试。 */
    PENDING,
    /** 事件已被某个发布器实例短事务领取，正在事务外发送RabbitMQ。 */
    SENDING,
    /** Broker确认接管消息并且消息没有因无法路由而被退回。 */
    PUBLISHED
}
