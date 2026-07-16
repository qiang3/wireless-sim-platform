package com.chenmingqiang.wirelesssim.task.domain;

import java.util.EnumSet;
import java.util.Set;

public enum TaskStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(TaskStatus target) {
        return allowedTargets().contains(target);
    }

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
