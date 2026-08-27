package cn.bitoffer.msgcenter.core.mapper;


import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import cn.bitoffer.msgcenter.core.model.MsgQueueTimerModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * MsgQueueTimerMapper。
 *
 * @author LQH
 */
@Mapper
public interface MsgQueueTimerMapper {

    void save(@Param("msgQueueTimerModel") MsgQueueTimerModel msgQueueTimerModel);

    List<MsgQueueTimerModel> getOnTimeMsgsList(@Param("status") int status, @Param("nowTimestamp") long nowTimestamp);

    void batchSetStatus(@Param("msgIdList") String msgIdList,@Param("status") int status);

    void setStatus(@Param("msgId") String msgId,@Param("status") int status);

    /** Locks a bounded batch of due (send_timestamp reached) rows with FOR UPDATE SKIP LOCKED. */
    List<MsgQueueTimerModel> selectOnTimeForUpdate(@Param("status") int status,
            @Param("nowTimestamp") long nowTimestamp, @Param("limit") int limit);

    /** Guarded flip: only rows still in {@code fromStatus} move to {@code toStatus}. */
    int claimByMsgIds(@Param("msgIdList") String msgIdList, @Param("fromStatus") int fromStatus,
            @Param("toStatus") int toStatus);

    /** Reclaims rows stuck in PROCESSING longer than {@code staleSeconds}. */
    int recoverStaleProcessing(@Param("fromStatus") int fromStatus, @Param("toStatus") int toStatus,
            @Param("staleSeconds") int staleSeconds);
}
