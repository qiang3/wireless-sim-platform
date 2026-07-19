package com.chenmingqiang.wirelesssim.task.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.OutboxStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实MySQL验证Outbox并发领取、状态更新和超时租约恢复。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.outbox.batch-size=2"
})
class OutboxClaimServiceIT {

    /** 被测试的Spring事务服务。 */
    @Autowired
    private OutboxClaimService outboxClaimService;
    /** 用于创建和回查测试事件的MyBatis代理。 */
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    /** 用于构造特定数据库状态以及测试结束后的定向清理。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 记录本测试类创建的事件，避免删除开发数据库中的其他数据。 */
    private final List<String> createdEventIds = new ArrayList<>();

    /** 每项测试结束后只删除本测试创建的Outbox记录。 */
    @AfterEach
    void cleanUp() {
        for (String eventId : createdEventIds) {
            jdbcTemplate.update("DELETE FROM outbox_event WHERE event_id = ?", eventId);
        }
    }

    /** 验证单批上限、到期条件以及领取后的完整状态变化。 */
    @Test
    void claimsOnlyReadyPendingEventsAndMarksThemSending() {
        OutboxEvent readyOne = insertEvent(91001L);
        OutboxEvent readyTwo = insertEvent(91002L);
        OutboxEvent future = insertEvent(91003L);
        jdbcTemplate.update(
                "UPDATE outbox_event SET next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR) WHERE id = ?",
                future.getId()
        );

        List<OutboxEvent> claimed = outboxClaimService.claimBatch("outbox-claim-it-ready");

        assertThat(claimed).extracting(OutboxEvent::getId)
                .containsExactly(readyOne.getId(), readyTwo.getId());
        assertThat(claimed).allSatisfy(event -> {
            assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENDING);
            assertThat(event.getClaimedBy()).isEqualTo("outbox-claim-it-ready");
            assertThat(event.getClaimedAt()).isNotNull();
            assertThat(event.getPublishAttempts()).isEqualTo(1);
            assertThat(event.getLastError()).isNull();
        });
        assertThat(outboxEventMapper.findByEventId(future.getEventId()).getStatus())
                .isEqualTo(OutboxStatus.PENDING);
    }

    /** 验证两个并行发布器不会领取同一条事件。 */
    @Test
    void concurrentPublishersClaimDisjointEvents() throws Exception {
        for (long taskId = 92001L; taskId <= 92004L; taskId++) {
            insertEvent(taskId);
        }
        CountDownLatch startGate = new CountDownLatch(1);

        CompletableFuture<List<OutboxEvent>> first = CompletableFuture.supplyAsync(
                () -> awaitAndClaim(startGate, "outbox-claim-it-concurrent-1")
        );
        CompletableFuture<List<OutboxEvent>> second = CompletableFuture.supplyAsync(
                () -> awaitAndClaim(startGate, "outbox-claim-it-concurrent-2")
        );
        startGate.countDown();

        List<OutboxEvent> firstBatch = first.get(10, TimeUnit.SECONDS);
        List<OutboxEvent> secondBatch = second.get(10, TimeUnit.SECONDS);
        // SKIP LOCKED允许某个发布器在锁竞争窗口内暂时看到空批次；生产调度器会在下一轮继续扫描。
        if (firstBatch.isEmpty()) {
            firstBatch = outboxClaimService.claimBatch("outbox-claim-it-concurrent-1-next-scan");
        }
        if (secondBatch.isEmpty()) {
            secondBatch = outboxClaimService.claimBatch("outbox-claim-it-concurrent-2-next-scan");
        }
        Set<Long> allClaimedIds = new HashSet<>();
        firstBatch.forEach(event -> allClaimedIds.add(event.getId()));
        int firstSize = allClaimedIds.size();
        secondBatch.forEach(event -> allClaimedIds.add(event.getId()));

        assertThat(firstBatch).hasSize(2);
        assertThat(secondBatch).hasSize(2);
        assertThat(allClaimedIds).hasSize(4);
        assertThat(firstSize).isEqualTo(2);
    }

    /** 验证只有超过租约的SENDING事件会恢复，仍在租约内的事件保持不变。 */
    @Test
    void recoversOnlyExpiredSendingClaims() {
        OutboxEvent expired = insertEvent(93001L);
        OutboxEvent fresh = insertEvent(93002L);
        assertThat(outboxClaimService.claimBatch("outbox-claim-it-recovery")).hasSize(2);
        jdbcTemplate.update(
                "UPDATE outbox_event SET claimed_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 10 MINUTE) WHERE id = ?",
                expired.getId()
        );

        int recoveredRows = outboxClaimService.recoverExpiredClaims();

        OutboxEvent recovered = outboxEventMapper.findByEventId(expired.getEventId());
        OutboxEvent stillSending = outboxEventMapper.findByEventId(fresh.getEventId());
        assertThat(recoveredRows).isEqualTo(1);
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recovered.getClaimedBy()).isNull();
        assertThat(recovered.getClaimedAt()).isNull();
        assertThat(recovered.getLastError()).contains("租约超时");
        assertThat(stillSending.getStatus()).isEqualTo(OutboxStatus.SENDING);
    }

    /** 让并发任务同时越过闸门后调用真实的Spring事务代理。 */
    private List<OutboxEvent> awaitAndClaim(CountDownLatch startGate, String publisherId) {
        try {
            if (!startGate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试开始超时");
            }
            return outboxClaimService.claimBatch(publisherId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试线程被中断", exception);
        }
    }

    /** 创建一条字段完整且业务唯一的待发布事件。 */
    private OutboxEvent insertEvent(Long taskId) {
        String eventId = UUID.randomUUID().toString();
        createdEventIds.add(eventId);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(taskId);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(1);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\":\"" + eventId + "\",\"taskId\":" + taskId + "}");
        event.setPriority(3);
        event.setOccurredAt(LocalDateTime.now());
        assertThat(outboxEventMapper.insertPending(event)).isEqualTo(1);
        // 开发库可能保留人工调试事件；把本测试数据排到最前，避免领取并修改非测试数据。
        jdbcTemplate.update(
                "UPDATE outbox_event SET next_attempt_at = '1000-01-01 00:00:00.000', "
                        + "created_at = '1000-01-01 00:00:00.000' WHERE id = ?",
                event.getId()
        );
        return event;
    }
}
