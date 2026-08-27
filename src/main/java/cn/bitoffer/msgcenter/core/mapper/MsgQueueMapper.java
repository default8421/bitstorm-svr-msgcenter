package cn.bitoffer.msgcenter.core.mapper;


import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MsgQueueMapper。
 *
 * @author LQH
 */
@Mapper
public interface MsgQueueMapper {

    void save(@Param("tableName") String tableName,@Param("msgQueueModel") MsgQueueModel msgQueueModel);

    MsgQueueModel getMsgById(@Param("tableName") String tableName,@Param("msgId") String msgId);

    List<MsgQueueModel> getMsgsByStatus(@Param("tableName") String tableName,@Param("status") int status,@Param("limit") int limit);

    void batchSetStatus(@Param("tableName") String tableName,@Param("msgIdList") String msgIdList,@Param("status") int status);

    void setStatus(@Param("tableName") String tableName,@Param("msgId") String msgId,@Param("status") int status);

    /**
     * Locks up to {@code limit} pending rows with {@code FOR UPDATE SKIP LOCKED} so concurrent
     * consumers each grab a disjoint set. Must be called inside a transaction together with
     * {@link #claimByMsgIds}.
     */
    List<MsgQueueModel> selectPendingForUpdate(@Param("tableName") String tableName,
            @Param("status") int status, @Param("limit") int limit);

    /** Guarded status flip: only rows still in {@code fromStatus} move to {@code toStatus}. */
    void claimByMsgIds(@Param("tableName") String tableName,
            @Param("msgIdList") String msgIdList, @Param("fromStatus") int fromStatus,
            @Param("toStatus") int toStatus);

    /** Reclaims rows stuck in {@code fromStatus} longer than {@code staleSeconds}. */
    int recoverStaleProcessing(@Param("tableName") String tableName,
            @Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus,
            @Param("staleSeconds") int staleSeconds);

    /**
     * Re-queues a message for retry with a bounded delay: sets the status and pushes
     * {@code next_attempt_at} out by {@code delaySeconds} so the claim query will not pick it up
     * again immediately (prevents retry storms without blocking any thread).
     */
    void setRetryScheduled(@Param("tableName") String tableName, @Param("msgId") String msgId,
            @Param("status") int status, @Param("delaySeconds") int delaySeconds);

    /**
     * Counts pending rows, stopping at {@code cap}.
     *
     * <p>The admission guard only needs to know whether the backlog crossed a watermark, so an
     * uncapped {@code COUNT(*)} would pay for scanning millions of index entries to answer a
     * yes/no question — exactly when the system is already overloaded.
     */
    long countPendingCapped(@Param("tableName") String tableName, @Param("status") int status,
            @Param("cap") int cap);

    /** Creation time of the oldest row in {@code status}, or null when the queue is empty. */
    java.util.Date oldestPendingCreateTime(@Param("tableName") String tableName,
            @Param("status") int status);
}
