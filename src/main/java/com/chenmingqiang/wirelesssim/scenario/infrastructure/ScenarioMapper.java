package com.chenmingqiang.wirelesssim.scenario.infrastructure;

import com.chenmingqiang.wirelesssim.scenario.domain.SimulationScenario;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// MyBatis说明：Spring会为该接口生成代理对象，方法由对应XML中的SQL实现。

/**
 * 场景持久化接口。MyBatis 根据同名 XML 中的 SQL 在运行时生成实现类。
 */
@Mapper
public interface ScenarioMapper {

    /** 插入新场景，并把数据库生成的主键回填到 scenario.id。 */
    int insert(SimulationScenario scenario);

    /** 按 id、ownerId 和有效状态查询，实现资源归属校验。 */
    SimulationScenario findActiveOwnedById(
            @Param("id") Long id,
            @Param("ownerId") Long ownerId
    );

    /** 统计某用户未归档的场景总数，用于分页元数据。 */
    long countActiveByOwnerId(@Param("ownerId") Long ownerId);

    /** 使用 offset/limit 分页查询某用户未归档的场景。 */
    List<SimulationScenario> findActivePageByOwnerId(
            @Param("ownerId") Long ownerId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /** 只有 ownerId 与旧 version 同时匹配才更新；返回 0 表示发生并发冲突。 */
    int updateOwnedWithVersion(SimulationScenario scenario);

    /** 判断场景是否已经形成任务引用，避免破坏历史任务快照关系。 */
    long countTasksByScenarioId(@Param("scenarioId") Long scenarioId);

    /** 把场景标记为已归档，不执行物理删除。 */
    int archiveOwned(@Param("id") Long id, @Param("ownerId") Long ownerId);
}
