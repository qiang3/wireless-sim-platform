package com.chenmingqiang.wirelesssim.task.infrastructure;

import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// MyBatis说明：Spring会为该接口生成代理对象，方法由对应XML中的SQL实现。

@Mapper
/**
 * 教学注释：本文件为 infrastructure/TaskMapper.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public interface TaskMapper {

    int insert(ExperimentTask task);

    ExperimentTask findByCreatorAndIdempotencyKey(
            @Param("creatorId") Long creatorId,
            @Param("idempotencyKey") String idempotencyKey
    );

    ExperimentTask findOwnedById(@Param("id") Long id, @Param("creatorId") Long creatorId);

    long countOwned(
            @Param("creatorId") Long creatorId,
            @Param("status") TaskStatus status,
            @Param("algorithm") TaskAlgorithm algorithm
    );

    List<ExperimentTask> findOwnedPage(
            @Param("creatorId") Long creatorId,
            @Param("status") TaskStatus status,
            @Param("algorithm") TaskAlgorithm algorithm,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    int cancelOwnedWithVersion(
            @Param("id") Long id,
            @Param("creatorId") Long creatorId,
            @Param("version") Integer version
    );

    int retryOwnedWithVersion(
            @Param("id") Long id,
            @Param("creatorId") Long creatorId,
            @Param("version") Integer version
    );

    ExperimentTask findById(@Param("id") Long id);

    int enqueuePending(@Param("id") Long id);

    int claimQueuedForExecution(@Param("id") Long id);

    /** 只有状态和执行轮次同时匹配时才把任务抢占为RUNNING。 */
    int claimQueuedForExecutionAttempt(
            @Param("id") Long id,
            @Param("expectedRetryCount") int expectedRetryCount
    );

    List<Long> findPendingCandidateIds(@Param("limit") int limit);

    List<Long> findQueuedCandidateIds(@Param("limit") int limit);

    TaskStatus findStatusById(@Param("id") Long id);

    /** 缓存失效链路在只有任务ID时查询任务所有者。 */
    Long findCreatorIdById(@Param("id") Long id);

    int updateRunningProgress(
            @Param("id") Long id,
            @Param("progress") int progress
    );

    int markSucceeded(@Param("id") Long id);

    int markFailed(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage
    );

    /** 重试耗尽时，只把相同业务轮次且尚未开始执行的任务标记为FAILED。 */
    int markMessageDeliveryExhausted(
            @Param("id") Long id,
            @Param("expectedRetryCount") int expectedRetryCount,
            @Param("errorMessage") String errorMessage
    );
}
