package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chenmingqiang.wirelesssim.task.application.OutboxClaimService;
import com.chenmingqiang.wirelesssim.task.application.OutboxPublishResultService;
import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证定时发布器正确编排领取、发送、结果落库和租约恢复。 */
class OutboxPublisherSchedulerTest {

    /** 验证一批事件逐条发送，并使用同一个实例ID记录结果。 */
    @Test
    void publishesClaimedBatchAndRecordsEachResult() {
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        OutboxMessagePublisher messagePublisher = mock(OutboxMessagePublisher.class);
        OutboxPublishResultService resultService = mock(OutboxPublishResultService.class);
        OutboxEvent first = event(701L, "event-701");
        OutboxEvent second = event(702L, "event-702");
        when(claimService.claimBatch("scheduler-test")).thenReturn(List.of(first, second));
        when(messagePublisher.publish(first)).thenReturn(OutboxPublishResult.ack());
        when(messagePublisher.publish(second)).thenReturn(
                OutboxPublishResult.failure(OutboxPublishOutcome.TIMEOUT, "timeout")
        );
        when(resultService.recordResult(any(), eq("scheduler-test"), any())).thenReturn(true);
        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(
                claimService, messagePublisher, resultService, "scheduler-test"
        );

        scheduler.publishOnce();

        verify(messagePublisher).publish(first);
        verify(messagePublisher).publish(second);
        verify(resultService).recordResult(first, "scheduler-test", OutboxPublishResult.ack());
        verify(resultService).recordResult(
                second,
                "scheduler-test",
                OutboxPublishResult.failure(OutboxPublishOutcome.TIMEOUT, "timeout")
        );
    }

    /** 验证异常发布会转换为SEND_FAILED，并继续交给结果服务安排重试。 */
    @Test
    void convertsUnexpectedPublishExceptionToSendFailedResult() {
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        OutboxMessagePublisher messagePublisher = mock(OutboxMessagePublisher.class);
        OutboxPublishResultService resultService = mock(OutboxPublishResultService.class);
        OutboxEvent event = event(703L, "event-703");
        when(claimService.claimBatch("scheduler-test")).thenReturn(List.of(event));
        when(messagePublisher.publish(event)).thenThrow(new IllegalStateException("unexpected"));
        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(
                claimService, messagePublisher, resultService, "scheduler-test"
        );

        scheduler.publishOnce();

        verify(resultService).recordResult(
                eq(event),
                eq("scheduler-test"),
                eq(OutboxPublishResult.failure(
                        OutboxPublishOutcome.SEND_FAILED,
                        "IllegalStateException: unexpected"
                ))
        );
    }

    /** 验证租约恢复入口委托给数据库事务服务。 */
    @Test
    void delegatesExpiredLeaseRecovery() {
        OutboxClaimService claimService = mock(OutboxClaimService.class);
        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(
                claimService,
                mock(OutboxMessagePublisher.class),
                mock(OutboxPublishResultService.class),
                "scheduler-test"
        );

        scheduler.recoverExpiredOnce();

        verify(claimService).recoverExpiredClaims();
    }

    /** 创建调度器单元测试使用的最小事件对象。 */
    private OutboxEvent event(Long id, String eventId) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setEventId(eventId);
        event.setPublishAttempts(1);
        return event;
    }
}
