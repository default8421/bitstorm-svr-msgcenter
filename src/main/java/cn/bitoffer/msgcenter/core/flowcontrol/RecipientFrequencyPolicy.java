package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.FlowControlProperties.Suppression;
import cn.bitoffer.msgcenter.core.redis.RedisRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户防打扰。
 *
 * <p>这是业务策略，不是系统限流，两者的处置方式完全不同：系统限流命中后消息要等待并最终发出去；
 * 防打扰命中后这条消息就不该发了，直接进 {@code SUPPRESSED} 终态，不排队也不重试。
 *
 * <p>高优先级（验证码、安全告警）不受营销防打扰约束——用户收不到验证码的代价远大于多收一条消息，
 * 但它仍然要过渠道硬限额那一层。
 *
 * @author LQH
 */
@Component
@Slf4j
public class RecipientFrequencyPolicy {

    private final RedisRateLimiter rateLimiter;
    private final FlowControlProperties properties;
    private final MeterRegistry meterRegistry;

    public RecipientFrequencyPolicy(RedisRateLimiter rateLimiter, FlowControlProperties properties,
            MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @return {@code true} 表示这条消息应当被抑制、不再发送
     */
    public boolean shouldSuppress(DispatchContext ctx, String templateId) {
        Suppression suppression = properties.getSuppression();
        if (!suppression.isEnabled()
                || ctx.priority() == PriorityEnum.PRIORITY_HIGH.getPriorty()) {
            return false;
        }

        String key = Constants.REDIS_KEY_RECIPIENT_FREQ + ctx.tenantId() + ":" + ctx.channel() + ":"
                + ctx.recipient() + ":" + templateId;
        long windowMillis = suppression.getWindowSeconds() * 1000L;
        boolean withinLimit;
        try {
            withinLimit = rateLimiter.isWithinLimit(key, suppression.getMaxPerWindow(),
                    windowMillis);
        } catch (RuntimeException e) {
            // 与配额相反，这里 fail-open：防打扰是"少发几条广告"的体贴，不是安全边界。Redis 抖动时
            // 因此扣掉用户真正想要的通知，代价比偶尔多发一条大得多。
            log.warn("防打扰计数失败，本条消息按不抑制处理 recipient={} channel={}", ctx.recipient(),
                    ctx.channel(), e);
            return false;
        }
        if (withinLimit) {
            return false;
        }
        meterRegistry.counter("msgcenter.msg.suppressed", "channel", String.valueOf(ctx.channel()),
                "priority", PriorityEnum.GetPriorityStr(ctx.priority())).increment();
        log.info("命中防打扰，消息不再发送 tenant={} channel={} recipient={} 窗口={}s 上限={}",
                ctx.tenantId(), ctx.channel(), ctx.recipient(), suppression.getWindowSeconds(),
                suppression.getMaxPerWindow());
        return true;
    }
}
