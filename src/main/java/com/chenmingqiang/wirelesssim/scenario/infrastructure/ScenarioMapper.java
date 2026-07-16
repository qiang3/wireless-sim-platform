package com.chenmingqiang.wirelesssim.scenario.infrastructure;

import com.chenmingqiang.wirelesssim.scenario.domain.SimulationScenario;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ScenarioMapper {

    int insert(SimulationScenario scenario);

    SimulationScenario findActiveOwnedById(
            @Param("id") Long id,
            @Param("ownerId") Long ownerId
    );

    long countActiveByOwnerId(@Param("ownerId") Long ownerId);

    List<SimulationScenario> findActivePageByOwnerId(
            @Param("ownerId") Long ownerId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    int updateOwnedWithVersion(SimulationScenario scenario);

    long countTasksByScenarioId(@Param("scenarioId") Long scenarioId);

    int archiveOwned(@Param("id") Long id, @Param("ownerId") Long ownerId);
}
