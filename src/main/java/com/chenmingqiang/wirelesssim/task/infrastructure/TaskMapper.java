package com.chenmingqiang.wirelesssim.task.infrastructure;

import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
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

    List<Long> findPendingCandidateIds(@Param("limit") int limit);

    List<Long> findQueuedCandidateIds(@Param("limit") int limit);

    TaskStatus findStatusById(@Param("id") Long id);

    int updateRunningProgress(
            @Param("id") Long id,
            @Param("progress") int progress
    );

    int markSucceeded(@Param("id") Long id);

    int markFailed(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage
    );
}
