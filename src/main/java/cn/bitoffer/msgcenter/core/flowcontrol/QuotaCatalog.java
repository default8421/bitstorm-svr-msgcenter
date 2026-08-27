package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.flowcontrol.FlowControlProperties.Limit;
import cn.bitoffer.msgcenter.core.mapper.GlobalQuotaMapper;
import cn.bitoffer.msgcenter.core.mapper.SourceQuotaMapper;
import cn.bitoffer.msgcenter.core.model.GlobalQuotaModel;
import cn.bitoffer.msgcenter.core.model.SourceQuotaModel;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 解析某条消息适用的三层限额。
 *
 * <p>渠道与租户限额继续以 {@code t_global_quota} / {@code t_source_quota} 为准，运维改表即可生效，
 * 不需要发版；YAML 只在表里查不到时兜底。为了不让每条消息都打一次数据库，结果在进程内缓存
 * {@value #CACHE_TTL_MILLIS} 毫秒——调整限额最多晚这么久生效，换来的是消费线程不被 DB 往返拖住。
 *
 * @author LQH
 */
@Component
@Slf4j
public class QuotaCatalog {

    private static final long CACHE_TTL_MILLIS = 30_000L;

    private final GlobalQuotaMapper globalQuotaMapper;
    private final SourceQuotaMapper sourceQuotaMapper;
    private final FlowControlProperties properties;
    private final Map<String, CachedLimit> cache = new ConcurrentHashMap<>();

    private Clock clock = Clock.systemUTC();

    public QuotaCatalog(GlobalQuotaMapper globalQuotaMapper, SourceQuotaMapper sourceQuotaMapper,
            FlowControlProperties properties) {
        this.globalQuotaMapper = globalQuotaMapper;
        this.sourceQuotaMapper = sourceQuotaMapper;
        this.properties = properties;
    }

    public Limit platformLimit() {
        return properties.getPlatform();
    }

    public Limit channelLimit(int channel) {
        return cached("ch:" + channel, () -> {
            GlobalQuotaModel quota = globalQuotaMapper.getGlobalQuota(channel);
            if (quota != null) {
                return toLimit(quota.getNum(), quota.getUnit());
            }
            Limit configured = properties.getChannels().get(channel);
            return configured != null ? configured : properties.getPlatform();
        });
    }

    public Limit tenantLimit(DispatchContext ctx) {
        return cached("tn:" + ctx.tenantQuotaKey(), () -> {
            SourceQuotaModel quota = sourceQuotaMapper.getSourceQuota(ctx.channel(), ctx.sourceId());
            if (quota != null) {
                return toLimit(quota.getNum(), quota.getUnit());
            }
            Limit configured = properties.getTenants().get(ctx.tenantQuotaKey());
            return configured != null ? configured : properties.getTenantDefault();
        });
    }

    /**
     * 把 {@code num 条 / unit 毫秒} 的窗口配置换算成令牌桶参数。
     *
     * <p>突发容量取一个窗口的量：既保留了原配置"一个窗口内可以打满"的语义，又不会像固定窗口那样
     * 在窗口边界放行两倍流量。
     */
    private static Limit toLimit(int num, int unitMillis) {
        if (num <= 0 || unitMillis <= 0) {
            return new Limit(0, 0);
        }
        return new Limit(num * 1000.0 / unitMillis, Math.max(1, num));
    }

    private Limit cached(String key, java.util.function.Supplier<Limit> loader) {
        long now = clock.millis();
        CachedLimit hit = cache.get(key);
        if (hit != null && now < hit.expiresAt()) {
            return hit.limit();
        }
        Limit limit;
        try {
            limit = loader.get();
        } catch (RuntimeException e) {
            if (hit != null) {
                log.warn("加载限额配置失败，沿用上一次缓存值 key={}", key, e);
                return hit.limit();
            }
            throw e;
        }
        cache.put(key, new CachedLimit(limit, now + CACHE_TTL_MILLIS));
        return limit;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private record CachedLimit(Limit limit, long expiresAt) {
    }
}
