package com.chenmingqiang.wirelesssim.task.application;

/**
 * 消费准备结果。
 *
 * @param outcome 结构化处理结论
 * @param detail 便于日志和排错的人类可读说明
 */
public record TaskMessagePreparationResult(
        TaskMessagePreparationOutcome outcome,
        String detail
) {
}
