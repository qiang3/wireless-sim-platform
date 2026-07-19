package com.chenmingqiang.wirelesssim.scenario.domain;

import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 domain/SimulationScenario.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class SimulationScenario {

    /** 字段说明：`id`保存该对象运行所需的依赖、配置或状态。 */
    private Long id;
    /** 字段说明：`ownerId`保存该对象运行所需的依赖、配置或状态。 */
    private Long ownerId;
    /** 字段说明：`name`保存该对象运行所需的依赖、配置或状态。 */
    private String name;
    /** 字段说明：`description`保存该对象运行所需的依赖、配置或状态。 */
    private String description;
    /** 字段说明：`objective`保存该对象运行所需的依赖、配置或状态。 */
    private ScenarioObjective objective;
    /** 字段说明：`configJson`保存该对象运行所需的依赖、配置或状态。 */
    private String configJson;
    /** 字段说明：`version`保存该对象运行所需的依赖、配置或状态。 */
    private Integer version;
    /** 字段说明：`archived`保存该对象运行所需的依赖、配置或状态。 */
    private Boolean archived;
    /** 字段说明：`createdAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime createdAt;
    /** 字段说明：`updatedAt`保存该对象运行所需的依赖、配置或状态。 */
    private LocalDateTime updatedAt;

    /** 方法说明：`getId`封装下面这段业务或转换逻辑。 */
    public Long getId() {
        return id;
    }

    /** 方法说明：`setId`封装下面这段业务或转换逻辑。 */
    public void setId(Long id) {
        this.id = id;
    }

    /** 方法说明：`getOwnerId`封装下面这段业务或转换逻辑。 */
    public Long getOwnerId() {
        return ownerId;
    }

    /** 方法说明：`setOwnerId`封装下面这段业务或转换逻辑。 */
    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    /** 方法说明：`getName`封装下面这段业务或转换逻辑。 */
    public String getName() {
        return name;
    }

    /** 方法说明：`setName`封装下面这段业务或转换逻辑。 */
    public void setName(String name) {
        this.name = name;
    }

    /** 方法说明：`getDescription`封装下面这段业务或转换逻辑。 */
    public String getDescription() {
        return description;
    }

    /** 方法说明：`setDescription`封装下面这段业务或转换逻辑。 */
    public void setDescription(String description) {
        this.description = description;
    }

    /** 方法说明：`getObjective`封装下面这段业务或转换逻辑。 */
    public ScenarioObjective getObjective() {
        return objective;
    }

    /** 方法说明：`setObjective`封装下面这段业务或转换逻辑。 */
    public void setObjective(ScenarioObjective objective) {
        this.objective = objective;
    }

    /** 方法说明：`getConfigJson`封装下面这段业务或转换逻辑。 */
    public String getConfigJson() {
        return configJson;
    }

    /** 方法说明：`setConfigJson`封装下面这段业务或转换逻辑。 */
    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    /** 方法说明：`getVersion`封装下面这段业务或转换逻辑。 */
    public Integer getVersion() {
        return version;
    }

    /** 方法说明：`setVersion`封装下面这段业务或转换逻辑。 */
    public void setVersion(Integer version) {
        this.version = version;
    }

    /** 方法说明：`getArchived`封装下面这段业务或转换逻辑。 */
    public Boolean getArchived() {
        return archived;
    }

    /** 方法说明：`setArchived`封装下面这段业务或转换逻辑。 */
    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    /** 方法说明：`getCreatedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** 方法说明：`setCreatedAt`封装下面这段业务或转换逻辑。 */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /** 方法说明：`getUpdatedAt`封装下面这段业务或转换逻辑。 */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    /** 方法说明：`setUpdatedAt`封装下面这段业务或转换逻辑。 */
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
