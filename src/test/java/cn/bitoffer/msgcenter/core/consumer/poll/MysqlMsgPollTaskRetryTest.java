package cn.bitoffer.msgcenter.core.consumer.poll;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.intThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.manager.DealMsgManager;
import cn.bitoffer.msgcenter.core.manager.SendMsgManager;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.retry.BackoffPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MysqlMsgPollTaskRetryTest。
 *
 * @author LQH
 */
class MysqlMsgPollTaskRetryTest {

    private final DealMsgManager dealMsgManager = mock(DealMsgManager.class);
    private final MsgQueueMapper msgQueueMapper = mock(MsgQueueMapper.class);
    private final MsgRecordMapper msgRecordMapper = mock(MsgRecordMapper.class);
    private final SendMsgManager sendMsgManager = mock(SendMsgManager.class);
    private final SendMsgConf sendMsgConf = mock(SendMsgConf.class);

    private MysqlMsgPollTask task;

    @BeforeEach
    void setUp() {
        task = new MysqlMsgPollTask();
        task.dealMsgManager = dealMsgManager;
        task.msgQueueMapper = msgQueueMapper;
        task.msgRecordMapper = msgRecordMapper;
        task.sendMsgManager = sendMsgManager;
        task.sendMsgConf = sendMsgConf;
        task.legacyRetryBackoff = new BackoffPolicy(2_000L, 300_000L, 2.0, 0.2, 5);
        when(sendMsgConf.getMaxRetryCount()).thenReturn(5);
    }

    private SendMsgReq req() {
        SendMsgReq req = new SendMsgReq();
        req.setMsgID("m-1");
        req.setPriority(PriorityEnum.PRIORITY_LOW.getPriorty());
        return req;
    }

    @Test
    void schedulesRetryWithABoundedDelayInsteadOfImmediateRequeue() {
        doThrow(new RuntimeException("send failed")).when(dealMsgManager)
                .DealOneMsg(org.mockito.ArgumentMatchers.any());
        MsgRecordModel record = new MsgRecordModel();
        record.setRetryCount(1);
        when(msgRecordMapper.getMsgById("m-1")).thenReturn(record);
        when(msgQueueMapper.getMsgById(anyString(), eq("m-1"))).thenReturn(new cn.bitoffer.msgcenter.core.model.MsgQueueModel());

        task.asyncHandleMsg(req());

        verify(msgRecordMapper).incrementRetryCount("m-1", 2);
        // next_attempt_at is pushed out by a positive delay (no immediate re-poll storm)
        verify(msgQueueMapper).setRetryScheduled(anyString(), eq("m-1"),
                eq(MsgStatus.Pending.getStatus()), intThat(delay -> delay > 0));
    }

    @Test
    void marksDeadWhenRetriesExhaustedWithoutRescheduling() {
        doThrow(new RuntimeException("send failed")).when(dealMsgManager)
                .DealOneMsg(org.mockito.ArgumentMatchers.any());
        MsgRecordModel record = new MsgRecordModel();
        record.setRetryCount(5);
        when(msgRecordMapper.getMsgById("m-1")).thenReturn(record);

        task.asyncHandleMsg(req());

        verify(msgRecordMapper).setStatus("m-1", MsgStatus.Failed.getStatus());
        verify(msgQueueMapper, never()).setRetryScheduled(anyString(), anyString(), anyInt(),
                anyInt());
    }

    @Test
    void toleratesMissingMsgRecordWithoutNullPointer() {
        doThrow(new RuntimeException("send failed")).when(dealMsgManager)
                .DealOneMsg(org.mockito.ArgumentMatchers.any());
        when(msgRecordMapper.getMsgById("m-1")).thenReturn(null);
        when(msgQueueMapper.getMsgById(anyString(), eq("m-1"))).thenReturn(null);

        task.asyncHandleMsg(req());

        verify(sendMsgManager).SendToMysql(org.mockito.ArgumentMatchers.any());
        verify(msgQueueMapper).setRetryScheduled(anyString(), eq("m-1"),
                eq(MsgStatus.Pending.getStatus()), intThat(delay -> delay > 0));
    }
}
