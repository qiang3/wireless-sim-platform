package com.chenmingqiang.wirelesssim.task.infrastructure;

import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

// MyBatis说明：Spring会为该接口生成代理对象，方法由对应XML中的SQL实现。

@Mapper
/**
 * 教学注释：本文件为 infrastructure/TaskExecutionMapper.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
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
