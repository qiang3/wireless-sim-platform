package com.chenmingqiang.wirelesssim.task.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 domain/SimulationResult.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class SimulationResult {

    /** 字段说明：`id`保存该对象运行所需的依赖、配置或状态。 */
    private Long id;
    /** 字段说明：`taskId`保存该对象运行所需的依赖、配置或状态。 */
    private Long taskId;
    /** 字段说明：`throughput`保存该对象运行所需的依赖、配置或状态。 */
    private BigDecimal throughput;
    /** 字段说明：`averageAoi`保存该对象运行所需的依赖、配置或状态。 */
    private BigDecimal averageAoi;
    /** 字段说明：`convergenceStep`保存该对象运行所需的依赖、配置或状态。 */
    private Integer convergenceStep;
    /** 字段说明：`metricsJson`保存该对象运行所需的依赖、配置或状态。 */
    private String metricsJson;
    /** 字段说明：`artifactPath`保存该对象运行所需的依赖、配置或状态。 */
    private String artifactPath;
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
    /** 方法说明：`getThroughput`封装下面这段业务或转换逻辑。 */
    public BigDecimal getThroughput() { return throughput; }
    /** 方法说明：`setThroughput`封装下面这段业务或转换逻辑。 */
    public void setThroughput(BigDecimal throughput) { this.throughput = throughput; }
    /** 方法说明：`getAverageAoi`封装下面这段业务或转换逻辑。 */
    public BigDecimal getAverageAoi() { return averageAoi; }
    /** 方法说明：`setAverageAoi`封装下面这段业务或转换逻辑。 */
    public void setAverageAoi(BigDecimal averageAoi) { this.averageAoi = averageAoi; }
    /** 方法说明：`getConvergenceStep`封装下面这段业务或转换逻辑。 */
    public Integer getConvergenceStep() { return convergenceStep; }
    /** 方法说明：`setConvergenceStep`封装下面这段业务或转换逻辑。 */
    public void setConvergenceStep(Integer convergenceStep) { this.convergenceStep = convergenceStep; }
    /** 方法说明：`getMetricsJson`封装下面这段业务或转换逻辑。 */
    public String getMetricsJson() { return metricsJson; }
    /** 方法说明：`setMetricsJson`封装下面这段业务或转换逻辑。 */
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    /** 方法说明：`getArtifactPath`封装下面这段业务或转换逻辑。 */
    public String getArtifactPath() { return artifactPath; }
    /** 方法说明：`setArtifactPath`封装下面这段业务或转换逻辑。 */
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    /** 方法说明：`getCreatedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** 方法说明：`setCreatedAt`封装下面这段业务或转换逻辑。 */
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
