package cn.bitoffer.msgcenter.core.flowcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.consumer.poll.MysqlMsgPollTask;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.redis.QuotaUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

/**
 * MysqlDispatchWorkerTest。
 *
 * @author LQH
 */
class MysqlDispatchWorkerTest {

    private final FlowControlProperties properties = new FlowControlProperties();
    private final FairDispatchScheduler scheduler =
            new FairDispatchScheduler(properties, new SimpleMeterRegistry());
    private final DispatchQuotaService quotaService = mock(DispatchQuotaService.class);
    private final RecipientFrequencyPolicy frequencyPolicy =
            mock(RecipientFrequencyPolicy.class);
    private final MysqlMsgPollTask pollTask = mock(MysqlMsgPollTask.class);

    private final MysqlDispatchWorker worker = new MysqlDispatchWorker(scheduler, quotaService,
            frequencyPolicy, pollTask, properties, new SimpleMeterRegistry());

    @BeforeEach
    void keepParksShort() {
        properties.getScheduler().setMaxParkMillis(1L);
        properties.getScheduler().setIdleParkMillis(1L);
        properties.getScheduler().setFailClosedParkMillis(1L);
    }

    @Test
    void pushesAMessageOnlyOnceQuotaIsGranted() {
        when(quotaService.acquire(any())).thenReturn(AcquireResult.allow());
        scheduler.offer(task("m-1"));

        assertThat(worker.dispatchOnce()).isTrue();

        verify(pollTask).asyncHandleMsg(any(SendMsgReq.class));
    }

    @Test
    void putsAThrottledMessageBackWithoutPushingOrFailingIt() {
        when(quotaService.acquire(any())).thenReturn(AcquireResult.retryAfter(50L));
        scheduler.offer(task("m-1"));

        worker.dispatchOnce();

        // 限流不是失败：不推送、不落失败终态、不进重试，消息原样等在队列里。
        verify(pollTask, never()).asyncHandleMsg(any());
        verify(pollTask, never()).markPermanentlyFailed(any());
        assertThat(scheduler.depth(PriorityEnum.PRIORITY_LOW.getPriorty())).isEqualTo(1);
    }

    @Test
    void stopsPushingAltogetherWhenTheQuotaServiceIsDown() {
        when(quotaService.acquire(any())).thenThrow(new QuotaUnavailableException("redis down"));
        scheduler.offer(task("m-1"));

        worker.dispatchOnce();

        // fail-closed：限流组件失灵时宁可停发，也不能放任流量打穿供应商配额。
        verify(pollTask, never()).asyncHandleMsg(any());
        assertThat(scheduler.depth(PriorityEnum.PRIORITY_LOW.getPriorty())).isEqualTo(1);
    }

    @Test
    void endsASuppressedMessageWithoutSpendingAToken() {
        when(frequencyPolicy.shouldSuppress(any(), any())).thenReturn(true);
        scheduler.offer(task("m-1"));

        worker.dispatchOnce();

        verify(pollTask).markSuppressed(any(SendMsgReq.class), any(DispatchContext.class));
        verify(quotaService, never()).acquire(any());
        verify(pollTask, never()).asyncHandleMsg(any());
    }

    @Test
    void keepsAMessageWhenThePushPoolIsFullInsteadOfDroppingIt() {
        when(quotaService.acquire(any())).thenReturn(AcquireResult.allow());
        org.mockito.Mockito.doThrow(new TaskRejectedException("pool full"))
                .when(pollTask).asyncHandleMsg(any());
        scheduler.offer(task("m-1"));

        worker.dispatchOnce();

        assertThat(scheduler.depth(PriorityEnum.PRIORITY_LOW.getPriorty())).isEqualTo(1);
    }

    @Test
    void handsAMessageBackToMysqlBeforeStaleRecoveryCanDuplicateIt() {
        properties.getScheduler().setMaxHoldMillis(10L);
        SendMsgReq req = new SendMsgReq();
        req.setMsgID("m-old");
        req.setPriority(PriorityEnum.PRIORITY_LOW.getPriorty());
        scheduler.offer(new DispatchTask(req, new DispatchContext("acme", "billing", 2,
                PriorityEnum.PRIORITY_LOW.getPriorty(), "to@example.com"),
                System.currentTimeMillis() - 5_000L));

        worker.dispatchOnce();

        verify(pollTask).releaseToPending(req);
        verify(pollTask, never()).asyncHandleMsg(any());
        assertThat(scheduler.depth(PriorityEnum.PRIORITY_LOW.getPriorty())).isZero();
    }

    @Test
    void reportsAnIdleSchedulerSoTheLoopCanBackOff() {
        assertThat(worker.dispatchOnce()).isFalse();
    }

    private static DispatchTask task(String msgId) {
        SendMsgReq req = new SendMsgReq();
        req.setMsgID(msgId);
        req.setPriority(PriorityEnum.PRIORITY_LOW.getPriorty());
        req.setTemplateId("t-1");
        return new DispatchTask(req, new DispatchContext("acme", "billing", 2,
                PriorityEnum.PRIORITY_LOW.getPriorty(), "to@example.com"),
                System.currentTimeMillis());
    }
}
