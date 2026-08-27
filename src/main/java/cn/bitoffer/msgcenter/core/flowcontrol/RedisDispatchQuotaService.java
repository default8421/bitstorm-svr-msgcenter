package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.FlowControlProperties.Limit;
import cn.bitoffer.msgcenter.core.redis.RedisTokenBucketLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 基于 Redis 分层令牌桶的发送配额实现。
 *
 * <p>三层配额同时判定，一次 Lua 调用完成：
 * <ul>
 *   <li>平台桶：保护中台自身（线程池、数据库、Redis）；</li>
 *   <li>渠道桶：短信、邮件、飞书各自的供应商硬限额，超了会被对方直接拒或封禁；</li>
 *   <li>租户桶：租户之间互不挤占，一个业务突然放量不会拖垮别人。</li>
 * </ul>
 *
 * <p>高优先级（验证码、告警）只跳过租户桶去借用空闲份额，平台桶和渠道桶一律照常扣减：这两层
 * 代表的是物理承载能力和对外合同，不是可以协商的业务额度。
 *
 * @author LQH
 */
@Service
@Slf4j
public class RedisDispatchQuotaService implements DispatchQuotaService {

    private final RedisTokenBucketLimiter limiter;
    private final QuotaCatalog quotaCatalog;
    private final MeterRegistry meterRegistry;

    private Clock clock = Clock.systemUTC();

    public RedisDispatchQuotaService(RedisTokenBucketLimiter limiter, QuotaCatalog quotaCatalog,
            MeterRegistry meterRegistry) {
        this.limiter = limiter;
        this.quotaCatalog = quotaCatalog;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public AcquireResult acquire(DispatchContext ctx) {
        List<BucketSpec> buckets = new ArrayList<>(3);
        addBucket(buckets, Constants.REDIS_KEY_DISPATCH_BUCKET + "platform",
                quotaCatalog.platformLimit());
        addBucket(buckets, Constants.REDIS_KEY_DISPATCH_BUCKET + "ch:" + ctx.channel(),
                quotaCatalog.channelLimit(ctx.channel()));
        if (!ctx.isHighPriority()) {
            addBucket(buckets, Constants.REDIS_KEY_DISPATCH_BUCKET + "tn:" + ctx.tenantQuotaKey(),
                    quotaCatalog.tenantLimit(ctx));
        }

        AcquireResult result = limiter.acquire(buckets, clock.millis(), 1);
        meterRegistry.counter("msgcenter.dispatch.quota",
                "channel", String.valueOf(ctx.channel()),
                "priority", PriorityEnum.GetPriorityStr(ctx.priority()),
                "result", result.granted() ? "granted" : "throttled").increment();
        if (!result.granted() && log.isDebugEnabled()) {
            log.debug("配额不足，消息继续排队 tenant={} channel={} 预计等待={}ms", ctx.tenantId(),
                    ctx.channel(), result.retryAfterMillis());
        }
        return result;
    }

    /**
     * qps 配成 0 是运维手动"停发"这一层的开关，会被当成永远取不到令牌的桶；只有完全没有这一层
     * 配置（null）才跳过。缺配置在 {@link QuotaCatalog} 里已经逐级兜底过了。
     */
    private static void addBucket(List<BucketSpec> buckets, String key, Limit limit) {
        if (limit == null) {
            return;
        }
        buckets.add(new BucketSpec(key, limit.getQps(), Math.max(limit.getBurst(), 1)));
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
