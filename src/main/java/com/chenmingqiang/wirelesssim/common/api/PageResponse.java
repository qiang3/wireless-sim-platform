package com.chenmingqiang.wirelesssim.common.api;

import java.util.List;

/**
 * 通用分页响应，避免不同列表接口各自设计分页字段。
 *
 * @param content 当前页实际数据
 * @param page 从0开始的页码
 * @param size 每页期望返回的最大条数
 * @param totalElements 满足查询条件的总记录数
 * @param totalPages 根据总记录数和每页条数计算出的总页数
 * @param <T> 列表元素类型
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    /**
     * 根据查询结果和总记录数创建分页响应。
     *
     * @param content 当前页数据
     * @param page 当前页码
     * @param size 每页条数，调用方必须保证大于0
     * @param totalElements 总记录数
     * @param <T> 元素类型
     * @return 包含总页数的分页响应
     */
    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        // 使用向上取整公式；没有记录时约定总页数为0。
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }
}
