package cn.bitoffer.msgcenter.core.constant;

/**
 * Constants。
 *
 * @author LQH
 */
public class Constants {
     public static final String  TableNamePre_MsgQueue= "t_msg_queue_";
     public static final String  Topic_Tail_MsgQueue= "-topic";

     public static final String  REDIS_KEY_SOURCE_QUOTA= "XMSG_source_quota_";
     public static final String  REDIS_KEY_RATE_LIMIT_COUNT= "XMSG_rate_limit_count";
     public static final String  REDIS_KEY_RATE_LIMIT_COUNT_TIMER= "XMSG_rate_limit_count_timer";
     public static final String  REDIS_KEY_TEMPLATE= "XMSG_template_";
     public static final String  REDIS_KEY_MES_RECORD= "XMSG_msgrecord_";

     /** 消费侧分层令牌桶的键前缀，后面拼 platform / ch:{channel} / tn:{tenant}:{source}:{channel}。 */
     public static final String  REDIS_KEY_DISPATCH_BUCKET= "XMSG_bucket:";
     /** 用户防打扰的滑动窗口计数键前缀。 */
     public static final String  REDIS_KEY_RECIPIENT_FREQ= "XMSG_recipient_freq:";

}
