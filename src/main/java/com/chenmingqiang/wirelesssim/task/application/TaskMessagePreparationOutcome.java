package com.chenmingqiang.wirelesssim.task.application;

/** RabbitMQ执行消息与MySQL任务当前状态比对后的结构化结果。 */
public enum TaskMessagePreparationOutcome {
    /** 消息轮次正确，任务已经可以由Worker尝试抢占。 */
    READY_TO_EXECUTE,
    /** 当前执行轮次已经运行、结束或取消，重复消息无需再次执行。 */
    ALREADY_HANDLED,
    /** 消息属于更早的业务执行轮次。 */
    STALE_ATTEMPT,
    /** 消息声称的轮次超过数据库当前期望轮次。 */
    FUTURE_ATTEMPT,
    /** 消息引用的任务在数据库中不存在。 */
    TASK_NOT_FOUND
}
