package cn.bitoffer.msgcenter.core.enums;

/**
 * MsgStatus。
 *
 * @author LQH
 */
public enum MsgStatus {
    Pending(1),
    Processiong(2),
    Succeed(3),
    Failed(4),
    /**
     * 被防打扰策略终结。
     *
     * <p>单独建一个终态，而不是复用 Failed：这条消息在系统层面没有任何东西出错，是业务规则主动
     * 决定不发。混进 Failed 会污染失败率指标，也会让它被重试逻辑捡起来反复尝试。
     */
    Suppressed(5);

    private MsgStatus(int status) {
        this.status = status;
    }
    private int status;

    public int getStatus() {
        return this.status;
    }
}
