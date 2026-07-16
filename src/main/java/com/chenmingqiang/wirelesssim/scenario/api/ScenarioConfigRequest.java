package com.chenmingqiang.wirelesssim.scenario.api;

import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ScenarioConfigRequest(
        @NotNull(message = "设备数量不能为空")
        @Min(value = 1, message = "设备数量不能小于1")
        @Max(value = 1000, message = "设备数量不能大于1000")
        Integer deviceCount,

        @NotNull(message = "天线数量不能为空")
        @Min(value = 1, message = "天线数量不能小于1")
        @Max(value = 128, message = "天线数量不能大于128")
        Integer antennaCount,

        @NotNull(message = "仿真时隙数量不能为空")
        @Min(value = 1, message = "仿真时隙数量不能小于1")
        @Max(value = 10_000_000, message = "仿真时隙数量不能大于10000000")
        Integer timeSlotCount,

        @NotNull(message = "业务数据到达率不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "业务数据到达率必须大于0")
        @DecimalMax(value = "1000000", message = "业务数据到达率不能大于1000000")
        BigDecimal dataArrivalRate,

        @NotNull(message = "平均绿色能量不能为空")
        @DecimalMin(value = "0.0", message = "平均绿色能量不能小于0")
        @DecimalMax(value = "1000000", message = "平均绿色能量不能大于1000000")
        BigDecimal averageGreenEnergy,

        @NotNull(message = "电池容量不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "电池容量必须大于0")
        @DecimalMax(value = "1000000", message = "电池容量不能大于1000000")
        BigDecimal batteryCapacity,

        @NotNull(message = "数据缓存容量不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "数据缓存容量必须大于0")
        @DecimalMax(value = "1000000", message = "数据缓存容量不能大于1000000")
        BigDecimal dataBufferCapacity,

        @NotNull(message = "WPT发射功率不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "WPT发射功率必须大于0")
        @DecimalMax(value = "1000000", message = "WPT发射功率不能大于1000000")
        BigDecimal wptTransmitPower,

        @NotNull(message = "设备最大发射功率不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "设备最大发射功率必须大于0")
        @DecimalMax(value = "1000000", message = "设备最大发射功率不能大于1000000")
        BigDecimal deviceMaxTransmitPower,

        @NotNull(message = "多址接入方式不能为空")
        AccessScheme accessScheme,

        @NotNull(message = "随机种子不能为空")
        Long randomSeed
) {
}
