package cn.bitoffer.msgcenter.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueTimerMapper;
import cn.bitoffer.msgcenter.core.model.MsgQueueTimerModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TimerMsgClaimServiceTest。
 *
 * @author LQH
 */
class TimerMsgClaimServiceTest {

    private final MsgQueueTimerMapper mapper = mock(MsgQueueTimerMapper.class);
    private final TimerMsgClaimService service = new TimerMsgClaimService(mapper);

    private MsgQueueTimerModel model(String msgId) {
        MsgQueueTimerModel m = new MsgQueueTimerModel();
        m.setMsgId(msgId);
        return m;
    }

    @Test
    void claimsOnTimePendingRowsAndGuardsTheFlipToProcessing() {
        when(mapper.selectOnTimeForUpdate(eq(MsgStatus.Pending.getStatus()), eq(1000L), anyInt()))
                .thenReturn(List.of(model("a"), model("b")));

        List<MsgQueueTimerModel> claimed = service.claim(1000L, 50);

        assertThat(claimed).extracting(MsgQueueTimerModel::getMsgId).containsExactly("a", "b");
        verify(mapper).claimByMsgIds(eq("('a','b')"), eq(MsgStatus.Pending.getStatus()),
                eq(MsgStatus.Processiong.getStatus()));
    }

    @Test
    void doesNothingWhenNoOnTimeRowsAreLocked() {
        when(mapper.selectOnTimeForUpdate(anyInt(), anyLong(), anyInt())).thenReturn(List.of());

        assertThat(service.claim(1000L, 50)).isEmpty();

        verify(mapper, never()).claimByMsgIds(anyString(), anyInt(), anyInt());
    }

    @Test
    void recoversStaleProcessingRowsBackToPending() {
        when(mapper.recoverStaleProcessing(MsgStatus.Processiong.getStatus(),
                MsgStatus.Pending.getStatus(), 120)).thenReturn(2);

        assertThat(service.recoverStale(120)).isEqualTo(2);
    }
}
