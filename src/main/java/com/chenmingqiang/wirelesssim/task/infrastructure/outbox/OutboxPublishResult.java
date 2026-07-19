package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

/**
 * 单条Outbox消息的发布结果。
 *
 * @param outcome 结构化结果类型，供阶段8.4第3步决定数据库状态
 * @param detail Broker原因、Return信息或异常摘要
 */
public record OutboxPublishResult(
        OutboxPublishOutcome outcome,
        String detail
) {

    /** 只有ACK才表示生产者可以尝试把Outbox标记为PUBLISHED。 */
    public boolean isSuccess() {
        return outcome == OutboxPublishOutcome.ACK;
    }

    /** 创建一个成功结果。 */
    public static OutboxPublishResult ack() {
        return new OutboxPublishResult(OutboxPublishOutcome.ACK, "Broker已确认且消息路由成功");
    }

    /** 创建一个失败结果并保留诊断信息。 */
    public static OutboxPublishResult failure(OutboxPublishOutcome outcome, String detail) {
        if (outcome == OutboxPublishOutcome.ACK) {
            throw new IllegalArgumentException("失败结果不能使用ACK类型");
        }
        return new OutboxPublishResult(outcome, detail);
    }
}
