package cn.bitoffer.msgcenter.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.BlockingDispatchGate;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContext;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContextResolver;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchThrottledException;
import cn.bitoffer.msgcenter.core.flowcontrol.RecipientFrequencyPolicy;
import cn.bitoffer.msgcenter.core.manager.DealMsgManager;
import cn.bitoffer.msgcenter.core.manager.SendMsgManager;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

/**
 * KafkaMsgConsumerTest。
 *
 * @author LQH
 */
class KafkaMsgConsumerTest {

    private final DealMsgManager dealMsgManager = mock(DealMsgManager.class);
    private final MsgRecordMapper msgRecordMapper = mock(MsgRecordMapper.class);
    private final MsgRecordService msgRecordService = mock(MsgRecordService.class);
    private final SendMsgManager sendMsgManager = mock(SendMsgManager.class);
    private final SendMsgConf sendMsgConf = mock(SendMsgConf.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final DispatchContextResolver contextResolver = mock(DispatchContextResolver.class);
    private final RecipientFrequencyPolicy frequencyPolicy =
            mock(RecipientFrequencyPolicy.class);
    private final BlockingDispatchGate dispatchGate = mock(BlockingDispatchGate.class);

    private KafkaMsgConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new KafkaMsgConsumer();
        consumer.dealMsgManager = dealMsgManager;
        consumer.msgRecordMapper = msgRecordMapper;
        consumer.msgRecordService = msgRecordService;
        consumer.sendMsgManager = sendMsgManager;
        consumer.sendMsgConf = sendMsgConf;
        consumer.dispatchContextResolver = contextResolver;
        consumer.recipientFrequencyPolicy = frequencyPolicy;
        consumer.blockingDispatchGate = dispatchGate;
        when(sendMsgConf.getMaxRetryCount()).thenReturn(5);
        when(contextResolver.resolve(any())).thenReturn(new DispatchContext("acme", "billing", 2,
                PriorityEnum.PRIORITY_LOW.getPriorty(), "to@example.com"));
        when(dispatchGate.awaitPermit(any())).thenReturn(true);
    }

    private ConsumerRecord<String, String> record(SendMsgReq req) {
        return new ConsumerRecord<>("low-topic", 0, 0L, "k", JSONUtil.toJsonString(req));
    }

    private SendMsgReq req() {
        SendMsgReq req = new SendMsgReq();
        req.setMsgID("m-1");
        req.setPriority(PriorityEnum.PRIORITY_LOW.getPriorty());
        req.setTemplateId("t-1");
        return req;
    }

    @Test
    void acknowledgesOnlyAfterSuccessfulProcessing() {
        consumer.consumeLow(record(req()), ack, "low-topic");

        verify(dealMsgManager).DealOneMsg(any(SendMsgReq.class));
        verify(ack).acknowledge();
        verify(sendMsgManager, never()).SendToMq(any());
    }

    @Test
    void routesToRetryTopicThenAcknowledgesWhenProcessingFails() {
        doThrow(new RuntimeException("push failed")).when(dealMsgManager)
                .DealOneMsg(any(SendMsgReq.class));
        MsgRecordModel record = new MsgRecordModel();
        record.setRetryCount(0);
        when(msgRecordMapper.getMsgById("m-1")).thenReturn(record);

        consumer.consumeLow(record(req()), ack, "low-topic");

        verify(msgRecordMapper).incrementRetryCount("m-1", 1);
        verify(sendMsgManager).SendToMq(any(SendMsgReq.class));
        verify(ack).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenRetryEnqueueFails() {
        doThrow(new RuntimeException("push failed")).when(dealMsgManager)
                .DealOneMsg(any(SendMsgReq.class));
        MsgRecordModel record = new MsgRecordModel();
        record.setRetryCount(0);
        when(msgRecordMapper.getMsgById("m-1")).thenReturn(record);
        doThrow(new RuntimeException("broker down")).when(sendMsgManager)
                .SendToMq(any(SendMsgReq.class));

        assertThatThrownBy(() -> consumer.consumeLow(record(req()), ack, "low-topic"))
                .isInstanceOf(RuntimeException.class);

        verify(ack, never()).acknowledge();
    }

    @Test
    void marksDeadAndAcknowledgesWhenRetriesExhausted() {
        doThrow(new RuntimeException("push failed")).when(dealMsgManager)
                .DealOneMsg(any(SendMsgReq.class));
        MsgRecordModel record = new MsgRecordModel();
        record.setRetryCount(5);
        when(msgRecordMapper.getMsgById("m-1")).thenReturn(record);

        consumer.consumeLow(record(req()), ack, "low-topic");

        verify(msgRecordMapper).setStatus("m-1", MsgStatus.Failed.getStatus());
        verify(sendMsgManager, never()).SendToMq(any());
        verify(ack).acknowledge();
    }

    @Test
    void toleratesMissingMsgRecordWithoutNullPointer() {
        doThrow(new RuntimeException("push failed")).when(dealMsgManager)
                .DealOneMsg(any(SendMsgReq.class));
        when(msgRecordMapper.getMsgById("m-1")).thenReturn(null);

        consumer.consumeLow(record(req()), ack, "low-topic");

        verify(sendMsgManager).SendToMq(any(SendMsgReq.class));
        verify(ack).acknowledge();
    }

    @Test
    void leavesTheOffsetUncommittedWhenQuotaIsNotAvailableInTime() {
        when(dispatchGate.awaitPermit(any())).thenReturn(false);

        assertThatThrownBy(() -> consumer.consumeLow(record(req()), ack, "low-topic"))
                .isInstanceOf(DispatchThrottledException.class);

        // 限流不是投递失败：既不能提交 offset，也不能计入重试或转发到 retry-topic。
        verify(ack, never()).acknowledge();
        verify(dealMsgManager, never()).DealOneMsg(any());
        verify(msgRecordMapper, never()).incrementRetryCount(any(), org.mockito.ArgumentMatchers
                .anyInt());
        verify(sendMsgManager, never()).SendToMq(any());
    }

    @Test
    void endsASuppressedMessageAsItsOwnTerminalStateRatherThanAFailure() {
        when(frequencyPolicy.shouldSuppress(any(), any())).thenReturn(true);

        consumer.consumeLow(record(req()), ack, "low-topic");

        verify(msgRecordService).recordTerminalState(any(SendMsgReq.class), any(DispatchContext.class),
                eq(MsgStatus.Suppressed));
        verify(dealMsgManager, never()).DealOneMsg(any());
        verify(sendMsgManager, never()).SendToMq(any());
        verify(ack).acknowledge();
    }

    @Test
    void acknowledgesAndSkipsEmptyRecordValue() {
        ConsumerRecord<String, String> empty =
                new ConsumerRecord<>("low-topic", 0, 0L, "k", null);

        consumer.consumeLow(empty, ack, "low-topic");

        verify(ack).acknowledge();
        verify(dealMsgManager, never()).DealOneMsg(any());
    }
}
