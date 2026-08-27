package cn.bitoffer.msgcenter.core.flowcontrol;

/**
 * 在给定预算内没能拿到发送许可。
 *
 * <p>这不是投递失败：抛出它意味着"现在还轮不到这条消息"，调用方必须让消息回到队列稍后再来，
 * 不能增加重试次数，也不能把它转进重试队列或死信。
 *
 * @author LQH
 */
public class DispatchThrottledException extends RuntimeException {

    public DispatchThrottledException(String message) {
        super(message);
    }
}
