package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublisherProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox事件的领取与过期租约恢复服务。
 *
 * <p>这里仅操作数据库，不连接RabbitMQ。调用者在本方法提交事务、释放行锁后，
 * 才会执行可能耗时的网络发送，从而保持数据库事务足够短。</p>
 */
@Service
public class OutboxClaimService {

    /** 数据库访问代理，负责执行加锁查询和状态变更SQL。 */
    private final OutboxEventMapper outboxEventMapper;
    /** 发布器参数，决定每批数量和租约时长。 */
    private final OutboxPublisherProperties properties;

    /** 由Spring构造器注入依赖。 */
    public OutboxClaimService(
            OutboxEventMapper outboxEventMapper,
            OutboxPublisherProperties properties
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.properties = properties;
    }

    /**
     * 为指定发布器领取一批事件。
     *
     * @param publisherId 当前应用实例的稳定标识，用于防止其他实例更新本批事件
     * @return 已经变为SENDING并包含数据库最新值的事件列表
     */
    @Transactional
    public List<OutboxEvent> claimBatch(String publisherId) {
        validatePublisherId(publisherId);

        List<OutboxEvent> candidates = outboxEventMapper.lockPublishCandidates(properties.batchSize());
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Long> eventIds = candidates.stream().map(OutboxEvent::getId).toList();
        int claimedRows = outboxEventMapper.markClaimed(eventIds, publisherId);
        if (claimedRows != eventIds.size()) {
            throw new IllegalStateException("Outbox领取数量与锁定数量不一致，事务将回滚");
        }

        List<OutboxEvent> claimedEvents = outboxEventMapper.findClaimedBy(eventIds, publisherId);
        if (claimedEvents.size() != eventIds.size()) {
            throw new IllegalStateException("Outbox领取后回查数量不一致，事务将回滚");
        }
        return claimedEvents;
    }

    /**
     * 恢复领取后因发布器宕机而超过租约的事件。
     *
     * @return 本次恢复的事件数量
     */
    @Transactional
    public int recoverExpiredClaims() {
        return outboxEventMapper.recoverExpiredClaims(
                properties.leaseSeconds(),
                "发布器租约超时，事件已恢复等待重新发布"
        );
    }

    /** 防止空标识或异常长标识进入claimed_by字段。 */
    private void validatePublisherId(String publisherId) {
        if (publisherId == null || publisherId.isBlank()) {
            throw new IllegalArgumentException("发布器实例标识不能为空");
        }
        if (publisherId.length() > 100) {
            throw new IllegalArgumentException("发布器实例标识不能超过100个字符");
        }
    }
}
