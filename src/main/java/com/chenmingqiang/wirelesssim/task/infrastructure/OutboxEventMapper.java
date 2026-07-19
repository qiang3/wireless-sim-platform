package com.chenmingqiang.wirelesssim.task.infrastructure;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Outbox事件MyBatis数据访问接口。
 *
 * <p>Spring会为该接口创建代理对象，方法对应
 * {@code mapper/task/OutboxEventMapper.xml}中的SQL。</p>
 */
@Mapper
public interface OutboxEventMapper {

    /**
     * 插入一条待发布事件。
     *
     * <p>状态、发布次数和下次发送时间使用V3定义的数据库默认值，
     * 插入成功后MyBatis把自增主键回填到event.id。</p>
     *
     * @param event 任务事务中创建的Outbox事件
     * @return 受影响行数，正常为1
     */
    int insertPending(OutboxEvent event);

    /** 根据全局事件ID查询完整Outbox记录。 */
    OutboxEvent findByEventId(@Param("eventId") String eventId);

    /**
     * 根据业务唯一键查询事件，用于验证同一任务尝试是否已经存在执行请求。
     */
    OutboxEvent findByBusinessKey(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") Long aggregateId,
            @Param("eventType") String eventType,
            @Param("attemptNo") Integer attemptNo
    );

    /**
     * 在当前事务中锁定一批已经到达发送时间的待发布事件。
     *
     * <p>SQL使用FOR UPDATE SKIP LOCKED：其他发布器已经锁定的行会被跳过，
     * 因此多个应用实例可以并行领取不同事件，而不会彼此等待或重复领取。</p>
     */
    List<OutboxEvent> lockPublishCandidates(@Param("limit") int limit);

    /**
     * 把本次锁定的事件标记为发送中，并登记领取者和领取时间。
     */
    int markClaimed(
            @Param("ids") List<Long> ids,
            @Param("publisherId") String publisherId
    );

    /**
     * 查询本发布器刚刚领取的事件，返回包含最新状态和发布次数的完整对象。
     */
    List<OutboxEvent> findClaimedBy(
            @Param("ids") List<Long> ids,
            @Param("publisherId") String publisherId
    );

    /**
     * 把超过租约时间仍停留在SENDING的事件恢复为PENDING。
     *
     * <p>这用于处理发布器领取后进程崩溃的情况，防止事件永久卡死。</p>
     */
    int recoverExpiredClaims(
            @Param("leaseSeconds") long leaseSeconds,
            @Param("errorMessage") String errorMessage
    );

    /**
     * 只有当前领取者仍拥有SENDING事件时，才把成功结果标记为PUBLISHED。
     */
    int markPublished(
            @Param("id") Long id,
            @Param("publisherId") String publisherId
    );

    /**
     * 只有当前领取者仍拥有SENDING事件时，才记录失败并安排下一次发送。
     */
    int rescheduleAfterFailure(
            @Param("id") Long id,
            @Param("publisherId") String publisherId,
            @Param("delayMillis") long delayMillis,
            @Param("errorMessage") String errorMessage
    );
}
