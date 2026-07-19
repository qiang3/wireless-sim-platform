package com.chenmingqiang.wirelesssim.scenario.api;

import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 教学注释：本文件为 api/ScenarioConfigRequest.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record ScenarioConfigRequest(
        // 校验说明：该输入不能为空。
        @NotNull(message = "设备数量不能为空")
        // 校验说明：限制数值的最小值。
        @Min(value = 1, message = "设备数量不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 1000, message = "设备数量不能大于1000")
        Integer deviceCount,

        // 校验说明：该输入不能为空。

        @NotNull(message = "天线数量不能为空")
        // 校验说明：限制数值的最小值。
        @Min(value = 1, message = "天线数量不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 128, message = "天线数量不能大于128")
        Integer antennaCount,

        // 校验说明：该输入不能为空。

        @NotNull(message = "仿真时隙数量不能为空")
        // 校验说明：限制数值的最小值。
        @Min(value = 1, message = "仿真时隙数量不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 10_000_000, message = "仿真时隙数量不能大于10000000")
        Integer timeSlotCount,

        // 校验说明：该输入不能为空。

        @NotNull(message = "业务数据到达率不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", inclusive = false, message = "业务数据到达率必须大于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1000000", message = "业务数据到达率不能大于1000000")
        BigDecimal dataArrivalRate,

        // 校验说明：该输入不能为空。

        @NotNull(message = "平均绿色能量不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", message = "平均绿色能量不能小于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1000000", message = "平均绿色能量不能大于1000000")
        BigDecimal averageGreenEnergy,

        // 校验说明：该输入不能为空。

        @NotNull(message = "电池容量不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", inclusive = false, message = "电池容量必须大于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1000000", message = "电池容量不能大于1000000")
        BigDecimal batteryCapacity,

        // 校验说明：该输入不能为空。

        @NotNull(message = "数据缓存容量不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", inclusive = false, message = "数据缓存容量必须大于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1000000", message = "数据缓存容量不能大于1000000")
        BigDecimal dataBufferCapacity,

        // 校验说明：该输入不能为空。

        @NotNull(message = "WPT发射功率不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", inclusive = false, message = "WPT发射功率必须大于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1000000", message = "WPT发射功率不能大于1000000")
        BigDecimal wptTransmitPower,

        // 校验说明：该输入不能为空。

        @NotNull(message = "设备最大发射功率不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", inclusive = false, message = "设备最大发射功率必须大于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1000000", message = "设备最大发射功率不能大于1000000")
        BigDecimal deviceMaxTransmitPower,

        // 校验说明：该输入不能为空。

        @NotNull(message = "多址接入方式不能为空")
        AccessScheme accessScheme,

        // 校验说明：该输入不能为空。

        @NotNull(message = "随机种子不能为空")
        Long randomSeed
) {
}
