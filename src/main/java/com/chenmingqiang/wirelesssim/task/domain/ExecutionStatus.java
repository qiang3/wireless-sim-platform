package com.chenmingqiang.wirelesssim.task.domain;

/**
 * 教学注释：本文件为 domain/ExecutionStatus.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public enum ExecutionStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** 方法说明：`isTerminal`封装下面这段业务或转换逻辑。 */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
