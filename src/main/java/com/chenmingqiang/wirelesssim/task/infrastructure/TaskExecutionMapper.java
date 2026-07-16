package com.chenmingqiang.wirelesssim.task.infrastructure;

import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskExecutionMapper {

    int insertRunning(TaskExecution execution);

    TaskExecution findByTaskIdAndAttemptNo(
            @Param("taskId") Long taskId,
            @Param("attemptNo") Integer attemptNo
    );

    int touchHeartbeat(@Param("id") Long id);

    int markCancelled(@Param("id") Long id);

    int markSucceeded(@Param("id") Long id);

    int markFailed(
            @Param("id") Long id,
            @Param("errorMessage") String errorMessage
    );

    java.util.List<TaskExecution> findTimedOutRunning(
            @Param("timeoutSeconds") long timeoutSeconds,
            @Param("limit") int limit
    );

    int markFailedIfTimedOut(
            @Param("id") Long id,
            @Param("timeoutSeconds") long timeoutSeconds,
            @Param("errorMessage") String errorMessage
    );
}
