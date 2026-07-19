package com.chenmingqiang.wirelesssim.task.domain;

import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 domain/TaskExecution.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class TaskExecution {

    /** 字段说明：`id`保存该对象运行所需的依赖、配置或状态。 */
    private Long id;
    /** 字段说明：`taskId`保存该对象运行所需的依赖、配置或状态。 */
    private Long taskId;
    /** 字段说明：`attemptNo`保存该对象运行所需的依赖、配置或状态。 */
    private Integer attemptNo;
    /** 字段说明：`workerId`保存该对象运行所需的依赖、配置或状态。 */
    private String workerId;
    /** 字段说明：`status`保存该对象运行所需的依赖、配置或状态。 */
    private ExecutionStatus status;
    /** 字段说明：`heartbeatAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime heartbeatAt;
    /** 字段说明：`startedAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime startedAt;
    /** 字段说明：`finishedAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime finishedAt;
    /** 字段说明：`errorMessage`保存该对象运行所需的依赖、配置或状态。 */
    private String errorMessage;
    /** 字段说明：`createdAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime createdAt;

    /** 方法说明：`getId`封装下面这段业务或转换逻辑。 */
    public Long getId() { return id; }
    /** 方法说明：`setId`封装下面这段业务或转换逻辑。 */
    public void setId(Long id) { this.id = id; }
    /** 方法说明：`getTaskId`封装下面这段业务或转换逻辑。 */
    public Long getTaskId() { return taskId; }
    /** 方法说明：`setTaskId`封装下面这段业务或转换逻辑。 */
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    /** 方法说明：`getAttemptNo`封装下面这段业务或转换逻辑。 */
    public Integer getAttemptNo() { return attemptNo; }
    /** 方法说明：`setAttemptNo`封装下面这段业务或转换逻辑。 */
    public void setAttemptNo(Integer attemptNo) { this.attemptNo = attemptNo; }
    /** 方法说明：`getWorkerId`封装下面这段业务或转换逻辑。 */
    public String getWorkerId() { return workerId; }
    /** 方法说明：`setWorkerId`封装下面这段业务或转换逻辑。 */
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    /** 方法说明：`getStatus`封装下面这段业务或转换逻辑。 */
    public ExecutionStatus getStatus() { return status; }
    /** 方法说明：`setStatus`封装下面这段业务或转换逻辑。 */
    public void setStatus(ExecutionStatus status) { this.status = status; }
    /** 方法说明：`getHeartbeatAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    /** 方法说明：`setHeartbeatAt`封装下面这段业务或转换逻辑。 */
    public void setHeartbeatAt(LocalDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    /** 方法说明：`getStartedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getStartedAt() { return startedAt; }
    /** 方法说明：`setStartedAt`封装下面这段业务或转换逻辑。 */
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    /** 方法说明：`getFinishedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getFinishedAt() { return finishedAt; }
    /** 方法说明：`setFinishedAt`封装下面这段业务或转换逻辑。 */
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    /** 方法说明：`getErrorMessage`封装下面这段业务或转换逻辑。 */
    public String getErrorMessage() { return errorMessage; }
    /** 方法说明：`setErrorMessage`封装下面这段业务或转换逻辑。 */
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    /** 方法说明：`getCreatedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** 方法说明：`setCreatedAt`封装下面这段业务或转换逻辑。 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
