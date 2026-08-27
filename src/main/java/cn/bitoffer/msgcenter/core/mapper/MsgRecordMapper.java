package cn.bitoffer.msgcenter.core.mapper;


import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MsgRecordMapper。
 *
 * @author LQH
 */
@Mapper
public interface MsgRecordMapper {

    void save(@Param("msgRecordModel") MsgRecordModel msgRecordModel);

    /**
     * Atomic insert-or-update-status in one round trip (INSERT ... ON DUPLICATE KEY UPDATE). The
     * first writer for a msgId inserts the full row; later writers only flip {@code status}, so the
     * immutable columns (source/channel/tenant) stay as first written.
     */
    void upsertStatus(@Param("msgRecordModel") MsgRecordModel msgRecordModel);

    void setStatus(@Param("msgId") String msgId,@Param("status") int status);

    void incrementRetryCount(@Param("msgId") String msgId,@Param("newCount") int newCount);

    MsgRecordModel getMsgById(@Param("msgId") String msgId);

    // ---- 中台运行总览 / 消息记录（读数据库，供 dashboard 与消息列表使用）----

    /** 最近 N 条消息记录（含格式化后的创建时间字符串）。 */
    List<Map<String, Object>> recentMessages(@Param("tenantId") String tenantId, @Param("limit") int limit);

    /** 指定时间之后，按状态分组的计数：每行 {status, cnt}。 */
    List<Map<String, Object>> statusCountsSince(@Param("tenantId") String tenantId, @Param("since") String since);

    /** 指定时间之后，按渠道分组的计数：每行 {channel, cnt}。 */
    List<Map<String, Object>> channelCountsSince(@Param("tenantId") String tenantId, @Param("since") String since);

    /** 指定时间之后，按业务来源分组的计数：每行 {source_id, cnt}。 */
    List<Map<String, Object>> sourceCountsSince(@Param("tenantId") String tenantId, @Param("since") String since);

    /** 累计消息总量。 */
    long countAll(@Param("tenantId") String tenantId);
}
