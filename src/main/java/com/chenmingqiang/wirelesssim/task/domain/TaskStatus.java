package com.chenmingqiang.wirelesssim.task.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * 教学注释：本文件为 domain/TaskStatus.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public enum TaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** 方法说明：`canTransitionTo`封装下面这段业务或转换逻辑。 */
    public boolean canTransitionTo(TaskStatus target) {
        return allowedTargets().contains(target);
    }

    /** 方法说明：`allowedTargets`封装下面这段业务或转换逻辑。 */
    private Set<TaskStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(QUEUED, CANCELLED);
            case QUEUED -> EnumSet.of(RUNNING, FAILED, CANCELLED);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED);
            case FAILED -> EnumSet.of(QUEUED);
            case SUCCEEDED, CANCELLED -> EnumSet.noneOf(TaskStatus.class);
        };
    }
}
