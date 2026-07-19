package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

/**
 * 重试/死信转发结果。
 *
 * @param outcome 结构化发布结果
 * @param detail 可读的Broker或异常说明
 */
public record TaskMessageForwardResult(
        TaskMessageForwardOutcome outcome,
        String detail
) {

    /** 只有ACK且没有Return才表示可以确认原消费消息。 */
    public boolean isSuccess() {
        return outcome == TaskMessageForwardOutcome.ACK;
    }

    /** 创建成功结果。 */
    public static TaskMessageForwardResult ack() {
        return new TaskMessageForwardResult(TaskMessageForwardOutcome.ACK, "Broker已确认且消息路由成功");
    }

    /** 创建失败结果。 */
    public static TaskMessageForwardResult failure(TaskMessageForwardOutcome outcome, String detail) {
        if (outcome == TaskMessageForwardOutcome.ACK) {
            throw new IllegalArgumentException("失败结果不能使用ACK");
        }
        return new TaskMessageForwardResult(outcome, detail);
    }
}
