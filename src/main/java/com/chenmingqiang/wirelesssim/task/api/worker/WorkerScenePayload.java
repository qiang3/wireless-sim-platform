package com.chenmingqiang.wirelesssim.task.api.worker;

import java.math.BigDecimal;

/** 固化单位后的RSMA场景参数，Python端不再猜测字段语义。 */
public record WorkerScenePayload(
        String accessScheme,
        int deviceCount,
        int antennaCount,
        boolean antennaCountUsedByModel,
        int timeSlotCount,
        BigDecimal dataArrivalRateMegabitPerSlot,
        BigDecimal averageGreenEnergyMilliJoule,
        BigDecimal batteryCapacityMilliJoule,
        BigDecimal dataBufferCapacityMegabit,
        BigDecimal wptTransmitPowerWatt,
        BigDecimal deviceMaxTransmitPowerMilliWatt
) {
}
