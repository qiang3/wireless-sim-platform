package com.chenmingqiang.wirelesssim.common.api;

/**
 * 所有HTTP接口共用的统一响应外壳。
 *
 * <p>{@code T}表示真实业务数据的类型，例如任务详情、分页结果或登录结果。</p>
 *
 * @param code 稳定的业务码；成功为OK，失败时用于前端区分错误类型
 * @param message 面向调用方的简短说明
 * @param data 成功时的业务数据；失败时通常为null
 * @param <T> 业务数据类型
 */
public record ApiResponse<T>(
        String code,
        String message,
        T data
) {

    /**
     * 创建成功响应，统一填入OK和success。
     *
     * @param data 要返回的业务数据
     * @param <T> 业务数据类型
     * @return 包装后的成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>("OK", "success", data);
    }

    /**
     * 创建失败响应。错误响应不携带业务数据，避免返回半成品对象。
     *
     * @param code 稳定业务错误码
     * @param message 错误说明
     * @param <T> 响应的泛型类型
     * @return data为null的失败响应
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
