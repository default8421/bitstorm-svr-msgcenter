package cn.bitoffer.msgcenter.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * MysqlMsgClaimServiceTest。
 *
 * @author LQH
 */
class MysqlMsgClaimServiceTest {

    private final MsgQueueMapper mapper = mock(MsgQueueMapper.class);
    private final MysqlMsgClaimService service = new MysqlMsgClaimService(mapper);

    private MsgQueueModel model(String msgId) {
        MsgQueueModel m = new MsgQueueModel();
        m.setMsgId(msgId);
        return m;
    }

    @Test
    void claimsLockedPendingRowsAndFlipsThemToProcessingInOneTransaction() {
        when(mapper.selectPendingForUpdate("t_msg_queue_low", MsgStatus.Pending.getStatus(), 10))
                .thenReturn(List.of(model("a"), model("b")));

        List<MsgQueueModel> claimed = service.claim("t_msg_queue_low", 10);

        assertThat(claimed).extracting(MsgQueueModel::getMsgId).containsExactly("a", "b");
        // guarded update: only rows still Pending flip to Processing, keyed by the locked msg_ids
        verify(mapper).claimByMsgIds(eq("t_msg_queue_low"), eq("('a','b')"),
                eq(MsgStatus.Pending.getStatus()), eq(MsgStatus.Processiong.getStatus()));
    }

    @Test
    void doesNothingWhenNoPendingRowsAreLocked() {
        when(mapper.selectPendingForUpdate(anyString(), anyInt(), anyInt())).thenReturn(List.of());

        assertThat(service.claim("t_msg_queue_low", 10)).isEmpty();

        verify(mapper, never()).claimByMsgIds(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void recoversStaleProcessingRowsBackToPending() {
        when(mapper.recoverStaleProcessing("t_msg_queue_low", MsgStatus.Processiong.getStatus(),
                MsgStatus.Pending.getStatus(), 120)).thenReturn(3);

        assertThat(service.recoverStale("t_msg_queue_low", 120)).isEqualTo(3);
    }
}
