package cn.bitoffer.msgcenter.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.common.redis.ReentrantDistributeLock;
import cn.bitoffer.msgcenter.core.consumer.poll.MysqlMsgPollTask;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContext;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContextResolver;
import cn.bitoffer.msgcenter.core.flowcontrol.FairDispatchScheduler;
import cn.bitoffer.msgcenter.core.flowcontrol.FlowControlProperties;
import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MysqlMsgConsumerLeaderTest。
 *
 * @author LQH
 */
class MysqlMsgConsumerLeaderTest {

    private final ReentrantDistributeLock lock = mock(ReentrantDistributeLock.class);
    private final MysqlMsgClaimService claimService = mock(MysqlMsgClaimService.class);
    private final MysqlMsgPollTask pollTask = mock(MysqlMsgPollTask.class);
    private final DispatchContextResolver contextResolver = mock(DispatchContextResolver.class);
    private final FlowControlProperties flowControlProperties = new FlowControlProperties();
    private final FairDispatchScheduler scheduler =
            new FairDispatchScheduler(flowControlProperties, new SimpleMeterRegistry());

    @BeforeEach
    void resolveToALowPriorityTenant() {
        when(contextResolver.resolve(any())).thenReturn(new DispatchContext("acme", "billing", 2,
                PriorityEnum.PRIORITY_LOW.getPriorty(), "to@example.com"));
    }

    private MysqlMsgConsumer consumer(Clock clock) {
        MysqlMsgConsumer consumer = new MysqlMsgConsumer();
        consumer.reentrantDistributeLock = lock;
        consumer.mysqlMsgClaimService = claimService;
        consumer.mysqlMsgPollTask = pollTask;
        consumer.fairDispatchScheduler = scheduler;
        consumer.dispatchContextResolver = contextResolver;
        consumer.setClock(clock);
        return consumer;
    }

    @Test
    void retriesLeadershipOnlyAfterIntervalWithoutBlocking() {
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(2_000_000L), ZoneOffset.UTC);
        when(lock.lockWithDog(anyString(), anyString(), anyLong())).thenReturn(false);
        MysqlMsgConsumer consumer = consumer(fixed);

        consumer.consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW, 10);
        consumer.consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW, 10);

        verify(lock, times(1)).lockWithDog(anyString(), anyString(), anyLong());
        verify(claimService, never()).claim(anyString(), anyInt());
    }

    @Test
    void atomicallyClaimsAndHandsMessagesToTheScheduler() {
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(2_000_000L), ZoneOffset.UTC);
        when(claimService.claim("t_msg_queue_low", 10)).thenReturn(List.of(pendingMessage("m-9")));
        MysqlMsgConsumer consumer = consumer(fixed);
        consumer.setLeader(PriorityEnum.PRIORITY_LOW, true);

        consumer.consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW, 10);

        verify(claimService).claim("t_msg_queue_low", 10);
        // 领取后不再直接推送：先进公平调度器，真正的发送时机由配额决定。
        assertThat(scheduler.poll().req().getMsgID()).isEqualTo("m-9");
        verify(pollTask, never()).asyncHandleMsg(any());
    }

    @Test
    void claimsNoMoreThanTheSchedulerCanHoldSoBacklogStaysInMysql() {
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(2_000_000L), ZoneOffset.UTC);
        flowControlProperties.getScheduler().setCapacityPerPriority(3);
        when(claimService.claim(anyString(), anyInt())).thenReturn(List.of());
        MysqlMsgConsumer consumer = consumer(fixed);
        consumer.setLeader(PriorityEnum.PRIORITY_LOW, true);

        consumer.consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW, 10);

        verify(claimService).claim("t_msg_queue_low", 3);
    }

    @Test
    void stopsClaimingAltogetherWhenTheSchedulerIsFull() {
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(2_000_000L), ZoneOffset.UTC);
        flowControlProperties.getScheduler().setCapacityPerPriority(1);
        when(claimService.claim("t_msg_queue_low", 1)).thenReturn(List.of(pendingMessage("m-1")));
        MysqlMsgConsumer consumer = consumer(fixed);
        consumer.setLeader(PriorityEnum.PRIORITY_LOW, true);
        consumer.consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW, 10);

        consumer.consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW, 10);

        verify(claimService, times(1)).claim(anyString(), anyInt());
    }

    @Test
    void marksAMessageWithNoResolvableTemplateAsFinallyFailedInsteadOfRetryingForever() {
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(2_000_000L), ZoneOffset.UTC);
        org.mockito.Mockito.reset(contextResolver);
        when(contextResolver.resolve(any())).thenThrow(new IllegalStateException("模板已删除"));
        when(claimService.claim("t_msg_queue_low", 10)).thenReturn(List.of(pendingMessage("m-7")));
        MysqlMsgConsumer consumer = consumer(fixed);
        consumer.setLeader(PriorityEnum.PRIORITY_LOW, true);

        consumer.consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW, 10);

        verify(pollTask).markPermanentlyFailed(any());
        assertThat(scheduler.poll()).isNull();
    }

    private static MsgQueueModel pendingMessage(String msgId) {
        MsgQueueModel model = new MsgQueueModel();
        model.setMsgId(msgId);
        model.setPriority(PriorityEnum.PRIORITY_LOW.getPriorty());
        return model;
    }
}
