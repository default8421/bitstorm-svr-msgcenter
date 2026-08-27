package cn.bitoffer.msgcenter.core.consumer;

import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueTimerMapper;
import cn.bitoffer.msgcenter.core.model.MsgQueueTimerModel;
import cn.bitoffer.msgcenter.core.utils.SQLUtil;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomically claims a batch of due timer-queue rows.
 *
 * <p>The previous "getOnTimeMsgsList then batchSetStatus" flow was racy: two consumers (or a leader
 * plus a stale ex-leader) could read the same due rows before either updated them, double-sending
 * the message. Here the select takes row locks via {@code FOR UPDATE SKIP LOCKED} and the guarded
 * update ({@code status = fromStatus}) runs in the same transaction, so each caller claims a
 * disjoint set exactly once regardless of leader state.
 *
 * @author LQH
 */
@Service
@Slf4j
public class TimerMsgClaimService {

    private final MsgQueueTimerMapper mapper;

    public TimerMsgClaimService(MsgQueueTimerMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public List<MsgQueueTimerModel> claim(long nowTimestamp, int pullNum) {
        List<MsgQueueTimerModel> pending = mapper.selectOnTimeForUpdate(
                MsgStatus.Pending.getStatus(), nowTimestamp, pullNum);
        if (pending == null || pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> msgIds = pending.stream().map(MsgQueueTimerModel::getMsgId)
                .collect(Collectors.toList());
        String msgIdListStr = SQLUtil.convertListToSQLString(msgIds);
        mapper.claimByMsgIds(msgIdListStr, MsgStatus.Pending.getStatus(),
                MsgStatus.Processiong.getStatus());
        return pending;
    }

    @Transactional
    public int recoverStale(int staleSeconds) {
        int recovered = mapper.recoverStaleProcessing(MsgStatus.Processiong.getStatus(),
                MsgStatus.Pending.getStatus(), staleSeconds);
        if (recovered > 0) {
            log.warn("recovered {} stale PROCESSING timer rows back to PENDING", recovered);
        }
        return recovered;
    }
}
