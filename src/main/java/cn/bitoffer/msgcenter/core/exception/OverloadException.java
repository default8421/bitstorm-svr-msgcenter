package cn.bitoffer.msgcenter.core.exception;

/**
 * 平台过载，本次请求未被受理。
 *
 * <p>这与"渠道发得慢"不是一回事：渠道慢的消息已经被可靠收下、在队列里等着发；抛出这个异常
 * 意味着中台连可靠收下都做不到了，必须让调用方稍后重试，而不是给一个虚假的成功。
 *
 * @author LQH
 */
public class OverloadException extends BusinessException {

    private final int retryAfterSeconds;

    public OverloadException(String message, int retryAfterSeconds) {
        super(ErrorCode.RateLimit_ERROR, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
