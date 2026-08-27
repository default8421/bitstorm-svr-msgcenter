package cn.bitoffer.msgcenter.core.redis;

/**
 * 配额服务不可用。
 *
 * <p>消费侧对这个异常必须 fail-closed：暂停真实渠道推送，让消息继续留在持久化队列里。如果反过来
 * fail-open，限流组件一挂就会把供应商配额打穿，后果比短暂停发严重得多。
 *
 * @author LQH
 */
public class QuotaUnavailableException extends RuntimeException {

    public QuotaUnavailableException(String message) {
        super(message);
    }

    public QuotaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
