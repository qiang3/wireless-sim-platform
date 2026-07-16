package com.chenmingqiang.wirelesssim.task.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class SimulationResult {

    private Long id;
    private Long taskId;
    private BigDecimal throughput;
    private BigDecimal averageAoi;
    private Integer convergenceStep;
    private String metricsJson;
    private String artifactPath;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public BigDecimal getThroughput() { return throughput; }
    public void setThroughput(BigDecimal throughput) { this.throughput = throughput; }
    public BigDecimal getAverageAoi() { return averageAoi; }
    public void setAverageAoi(BigDecimal averageAoi) { this.averageAoi = averageAoi; }
    public Integer getConvergenceStep() { return convergenceStep; }
    public void setConvergenceStep(Integer convergenceStep) { this.convergenceStep = convergenceStep; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String metricsJson) { this.metricsJson = metricsJson; }
    public String getArtifactPath() { return artifactPath; }
    public void setArtifactPath(String artifactPath) { this.artifactPath = artifactPath; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
