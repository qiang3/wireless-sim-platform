package com.chenmingqiang.wirelesssim.task.infrastructure;

import com.chenmingqiang.wirelesssim.task.domain.SimulationResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SimulationResultMapper {

    int insert(SimulationResult result);

    SimulationResult findOwnedByTaskId(
            @Param("taskId") Long taskId,
            @Param("creatorId") Long creatorId
    );
}
