package cn.bitoffer.msgcenter.core.flowcontrol;

/**
 * 消费侧发送配额。
 *
 * <p>这是整套流控唯一决定"这一刻能不能真的推给用户"的地方。入口只负责把消息可靠地收下来，
 * 节奏由这里按平台、渠道、租户三层配额控制。
 *
 * @author LQH
 */
public interface DispatchQuotaService {

    /**
     * 为一条消息申请发送许可。
     *
     * <p>拒绝不等于失败：调用方应当让消息留在队列里稍后再试，不要计入重试次数，也不要写失败终态。
     */
    AcquireResult acquire(DispatchContext ctx);
}
