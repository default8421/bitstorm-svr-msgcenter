package cn.bitoffer.msgcenter.core.consumer;

import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import cn.bitoffer.msgcenter.core.utils.SQLUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Atomically claims a batch of pending MySQL-queue rows.
 *
 * <p>The previous "select by status, then batch update" approach was racy: two consumers could read
 * the same pending rows before either updated them, double-processing the message. Here the select
 * takes row locks via {@code FOR UPDATE SKIP LOCKED} and the guarded update ({@code status =
 * fromStatus}) runs in the same transaction, so each consumer claims a disjoint set exactly once.
 *
 * @author LQH
 */
@Service
@Slf4j
public class MysqlMsgClaimService {

    private final MsgQueueMapper msgQueueMapper;

    public MysqlMsgClaimService(MsgQueueMapper msgQueueMapper) {
        this.msgQueueMapper = msgQueueMapper;
    }

    @Transactional
    public List<MsgQueueModel> claim(String tableName, int pullNum) {
        List<MsgQueueModel> pending = msgQueueMapper.selectPendingForUpdate(tableName,
                MsgStatus.Pending.getStatus(), pullNum);
        if (pending == null || pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> msgIds = pending.stream().map(MsgQueueModel::getMsgId)
                .collect(Collectors.toList());
        String msgIdListStr = SQLUtil.convertListToSQLString(msgIds);
        msgQueueMapper.claimByMsgIds(tableName, msgIdListStr, MsgStatus.Pending.getStatus(),
                MsgStatus.Processiong.getStatus());
        return pending;
    }

    @Transactional
    public int recoverStale(String tableName, int staleSeconds) {
        int recovered = msgQueueMapper.recoverStaleProcessing(tableName,
                MsgStatus.Processiong.getStatus(), MsgStatus.Pending.getStatus(), staleSeconds);
        if (recovered > 0) {
            log.warn("recovered {} stale PROCESSING rows in {} back to PENDING", recovered,
                    tableName);
        }
        return recovered;
    }
}
