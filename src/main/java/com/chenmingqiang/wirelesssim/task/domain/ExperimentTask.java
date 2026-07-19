package com.chenmingqiang.wirelesssim.task.domain;

import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 domain/ExperimentTask.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class ExperimentTask {

    /** 字段说明：`id`保存该对象运行所需的依赖、配置或状态。 */
    private Long id;
    /** 字段说明：`taskNo`保存该对象运行所需的依赖、配置或状态。 */
    private String taskNo;
    /** 字段说明：`scenarioId`保存该对象运行所需的依赖、配置或状态。 */
    private Long scenarioId;
    /** 字段说明：`scenarioSnapshotJson`保存该对象运行所需的依赖、配置或状态。 */
    private String scenarioSnapshotJson;
    /** 字段说明：`creatorId`保存该对象运行所需的依赖、配置或状态。 */
    private Long creatorId;
    /** 字段说明：`algorithm`保存该对象运行所需的依赖、配置或状态。 */
    private TaskAlgorithm algorithm;
    /** 字段说明：`trainingConfigJson`保存该对象运行所需的依赖、配置或状态。 */
    private String trainingConfigJson;
    /** 字段说明：`priority`保存该对象运行所需的依赖、配置或状态。 */
    private Integer priority;
    /** 字段说明：`status`保存该对象运行所需的依赖、配置或状态。 */
    private TaskStatus status;
    /** 字段说明：`progress`保存该对象运行所需的依赖、配置或状态。 */
    private Integer progress;
    /** 字段说明：`retryCount`保存该对象运行所需的依赖、配置或状态。 */
    private Integer retryCount;
    /** 字段说明：`maxRetryCount`保存该对象运行所需的依赖、配置或状态。 */
    private Integer maxRetryCount;
    /** 字段说明：`idempotencyKey`保存该对象运行所需的依赖、配置或状态。 */
    private String idempotencyKey;
    /** 字段说明：`requestHash`保存该对象运行所需的依赖、配置或状态。 */
    private String requestHash;
    /** 字段说明：`errorMessage`保存该对象运行所需的依赖、配置或状态。 */
    private String errorMessage;
    /** 字段说明：`lockVersion`保存该对象运行所需的依赖、配置或状态。 */
    private Integer lockVersion;
    /** 字段说明：`submittedAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime submittedAt;
    /** 字段说明：`startedAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime startedAt;
    /** 字段说明：`finishedAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime finishedAt;
    /** 字段说明：`createdAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime createdAt;
    /** 字段说明：`updatedAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime updatedAt;

    /** 方法说明：`getId`封装下面这段业务或转换逻辑。 */
    public Long getId() { return id; }
    /** 方法说明：`setId`封装下面这段业务或转换逻辑。 */
    public void setId(Long id) { this.id = id; }
    /** 方法说明：`getTaskNo`封装下面这段业务或转换逻辑。 */
    public String getTaskNo() { return taskNo; }
    /** 方法说明：`setTaskNo`封装下面这段业务或转换逻辑。 */
    public void setTaskNo(String taskNo) { this.taskNo = taskNo; }
    /** 方法说明：`getScenarioId`封装下面这段业务或转换逻辑。 */
    public Long getScenarioId() { return scenarioId; }
    /** 方法说明：`setScenarioId`封装下面这段业务或转换逻辑。 */
    public void setScenarioId(Long scenarioId) { this.scenarioId = scenarioId; }
    /** 方法说明：`getScenarioSnapshotJson`封装下面这段业务或转换逻辑。 */
    public String getScenarioSnapshotJson() { return scenarioSnapshotJson; }
    /** 方法说明：`setScenarioSnapshotJson`封装下面这段业务或转换逻辑。 */
    public void setScenarioSnapshotJson(String scenarioSnapshotJson) { this.scenarioSnapshotJson = scenarioSnapshotJson; }
    /** 方法说明：`getCreatorId`封装下面这段业务或转换逻辑。 */
    public Long getCreatorId() { return creatorId; }
    /** 方法说明：`setCreatorId`封装下面这段业务或转换逻辑。 */
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    /** 方法说明：`getAlgorithm`封装下面这段业务或转换逻辑。 */
    public TaskAlgorithm getAlgorithm() { return algorithm; }
    /** 方法说明：`setAlgorithm`封装下面这段业务或转换逻辑。 */
    public void setAlgorithm(TaskAlgorithm algorithm) { this.algorithm = algorithm; }
    /** 方法说明：`getTrainingConfigJson`封装下面这段业务或转换逻辑。 */
    public String getTrainingConfigJson() { return trainingConfigJson; }
    /** 方法说明：`setTrainingConfigJson`封装下面这段业务或转换逻辑。 */
    public void setTrainingConfigJson(String trainingConfigJson) { this.trainingConfigJson = trainingConfigJson; }
    /** 方法说明：`getPriority`封装下面这段业务或转换逻辑。 */
    public Integer getPriority() { return priority; }
    /** 方法说明：`setPriority`封装下面这段业务或转换逻辑。 */
    public void setPriority(Integer priority) { this.priority = priority; }
    /** 方法说明：`getStatus`封装下面这段业务或转换逻辑。 */
    public TaskStatus getStatus() { return status; }
    /** 方法说明：`setStatus`封装下面这段业务或转换逻辑。 */
    public void setStatus(TaskStatus status) { this.status = status; }
    /** 方法说明：`getProgress`封装下面这段业务或转换逻辑。 */
    public Integer getProgress() { return progress; }
    /** 方法说明：`setProgress`封装下面这段业务或转换逻辑。 */
    public void setProgress(Integer progress) { this.progress = progress; }
    /** 方法说明：`getRetryCount`封装下面这段业务或转换逻辑。 */
    public Integer getRetryCount() { return retryCount; }
    /** 方法说明：`setRetryCount`封装下面这段业务或转换逻辑。 */
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    /** 方法说明：`getMaxRetryCount`封装下面这段业务或转换逻辑。 */
    public Integer getMaxRetryCount() { return maxRetryCount; }
    /** 方法说明：`setMaxRetryCount`封装下面这段业务或转换逻辑。 */
    public void setMaxRetryCount(Integer maxRetryCount) { this.maxRetryCount = maxRetryCount; }
    /** 方法说明：`getIdempotencyKey`封装下面这段业务或转换逻辑。 */
    public String getIdempotencyKey() { return idempotencyKey; }
    /** 方法说明：`setIdempotencyKey`封装下面这段业务或转换逻辑。 */
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    /** 方法说明：`getRequestHash`封装下面这段业务或转换逻辑。 */
    public String getRequestHash() { return requestHash; }
    /** 方法说明：`setRequestHash`封装下面这段业务或转换逻辑。 */
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    /** 方法说明：`getErrorMessage`封装下面这段业务或转换逻辑。 */
    public String getErrorMessage() { return errorMessage; }
    /** 方法说明：`setErrorMessage`封装下面这段业务或转换逻辑。 */
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    /** 方法说明：`getLockVersion`封装下面这段业务或转换逻辑。 */
    public Integer getLockVersion() { return lockVersion; }
    /** 方法说明：`setLockVersion`封装下面这段业务或转换逻辑。 */
    public void setLockVersion(Integer lockVersion) { this.lockVersion = lockVersion; }
    /** 方法说明：`getSubmittedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    /** 方法说明：`setSubmittedAt`封装下面这段业务或转换逻辑。 */
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    /** 方法说明：`getStartedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getStartedAt() { return startedAt; }
    /** 方法说明：`setStartedAt`封装下面这段业务或转换逻辑。 */
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    /** 方法说明：`getFinishedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getFinishedAt() { return finishedAt; }
    /** 方法说明：`setFinishedAt`封装下面这段业务或转换逻辑。 */
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    /** 方法说明：`getCreatedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** 方法说明：`setCreatedAt`封装下面这段业务或转换逻辑。 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    /** 方法说明：`getUpdatedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    /** 方法说明：`setUpdatedAt`封装下面这段业务或转换逻辑。 */
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
