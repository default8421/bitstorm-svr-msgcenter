package cn.bitoffer.msgcenter.core.flowcontrol;

/**
 * 取令牌的结果。
 *
 * <p>注意 {@code retryAfterMillis} 表达的是"下一枚令牌大约什么时候可用"，由真实供需算出，
 * 不是一个写死的等待时间。调用方据此让消息继续留在持久化队列里等待，而不是把它当成发送失败。
 *
 * @author LQH
 */
public record AcquireResult(boolean granted, long retryAfterMillis) {

    private static final AcquireResult GRANTED = new AcquireResult(true, 0L);

    public static AcquireResult allow() {
        return GRANTED;
    }

    public static AcquireResult retryAfter(long millis) {
        return new AcquireResult(false, Math.max(1L, millis));
    }
}
