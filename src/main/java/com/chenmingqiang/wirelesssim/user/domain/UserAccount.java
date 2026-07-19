package com.chenmingqiang.wirelesssim.user.domain;

import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 domain/UserAccount.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class UserAccount {

    /** 字段说明：`id`保存该对象运行所需的依赖、配置或状态。 */
    private Long id;
    /** 字段说明：`username`保存该对象运行所需的依赖、配置或状态。 */
    private String username;
    /** 字段说明：`passwordHash`保存该对象运行所需的依赖、配置或状态。 */
    private String passwordHash;
    /** 字段说明：`role`保存该对象运行所需的依赖、配置或状态。 */
    private UserRole role;
    /** 字段说明：`status`保存该对象运行所需的依赖、配置或状态。 */
    private UserStatus status;
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

    /** 方法说明：`getUsername`封装下面这段业务或转换逻辑。 */
    public String getUsername() {
        return username;
    }

    /** 方法说明：`setUsername`封装下面这段业务或转换逻辑。 */
    public void setUsername(String username) {
        this.username = username;
    }

    /** 方法说明：`getPasswordHash`封装下面这段业务或转换逻辑。 */
    public String getPasswordHash() {
        return passwordHash;
    }

    /** 方法说明：`setPasswordHash`封装下面这段业务或转换逻辑。 */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    /** 方法说明：`getRole`封装下面这段业务或转换逻辑。 */
    public UserRole getRole() {
        return role;
    }

    /** 方法说明：`setRole`封装下面这段业务或转换逻辑。 */
    public void setRole(UserRole role) {
        this.role = role;
    }

    /** 方法说明：`getStatus`封装下面这段业务或转换逻辑。 */
    public UserStatus getStatus() {
        return status;
    }

    /** 方法说明：`setStatus`封装下面这段业务或转换逻辑。 */
    public void setStatus(UserStatus status) {
        this.status = status;
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
