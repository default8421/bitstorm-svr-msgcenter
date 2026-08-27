package cn.bitoffer.msgcenter.core.redis;

import cn.bitoffer.msgcenter.core.flowcontrol.AcquireResult;
import cn.bitoffer.msgcenter.core.flowcontrol.BucketSpec;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 跨多个令牌桶的原子取令牌器。
 *
 * <p>与固定窗口计数相比，令牌桶按时间连续补充，不会在窗口切换的瞬间放行两倍流量；同时保留
 * {@code burst} 容量吸收短时抖动。
 *
 * <p>脚本必须一次性判断全部桶：先算出每个桶当前可用令牌，只有所有桶都够才统一扣减。若分开
 * 扣减，平台桶扣成功、渠道桶失败时，那一枚平台令牌就被白白吃掉，长期会把真实吞吐压到配置值
 * 以下。失败时脚本不写入任何状态，只回报最久的那个桶还要等多久。
 *
 * @author LQH
 */
@Component
@Slf4j
public class RedisTokenBucketLimiter {

    static final String ACQUIRE_SCRIPT =
            "local now = tonumber(ARGV[1])\n"
            + "local permits = tonumber(ARGV[2])\n"
            + "local n = #KEYS\n"
            + "local rates = {}\n"
            + "local bursts = {}\n"
            + "local avail = {}\n"
            + "local wait = 0\n"
            + "for i = 1, n do\n"
            + "  local rate = tonumber(ARGV[2 + (i - 1) * 2 + 1])\n"
            + "  local burst = tonumber(ARGV[2 + (i - 1) * 2 + 2])\n"
            + "  if rate <= 0 then\n"
            + "    return {0, 1000}\n"
            + "  end\n"
            + "  if burst < permits then\n"
            + "    burst = permits\n"
            + "  end\n"
            + "  rates[i] = rate\n"
            + "  bursts[i] = burst\n"
            + "  local state = redis.call('HMGET', KEYS[i], 'tokens', 'ts')\n"
            + "  local tokens = tonumber(state[1])\n"
            + "  local ts = tonumber(state[2])\n"
            + "  if tokens == nil or ts == nil then\n"
            + "    tokens = burst\n"
            + "    ts = now\n"
            + "  end\n"
            + "  local elapsed = now - ts\n"
            + "  if elapsed > 0 then\n"
            + "    tokens = math.min(burst, tokens + (elapsed * rate) / 1000.0)\n"
            + "  end\n"
            + "  avail[i] = tokens\n"
            + "  if tokens < permits then\n"
            + "    local w = math.ceil(((permits - tokens) * 1000.0) / rate)\n"
            + "    if w > wait then\n"
            + "      wait = w\n"
            + "    end\n"
            + "  end\n"
            + "end\n"
            + "if wait > 0 then\n"
            + "  return {0, wait}\n"
            + "end\n"
            + "for i = 1, n do\n"
            + "  local ttl = math.ceil((bursts[i] / rates[i]) * 1000) + 60000\n"
            + "  redis.call('HMSET', KEYS[i], 'tokens', avail[i] - permits, 'ts', now)\n"
            + "  redis.call('PEXPIRE', KEYS[i], ttl)\n"
            + "end\n"
            + "return {1, 0}";

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> script;

    public RedisTokenBucketLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(ACQUIRE_SCRIPT, List.class);
    }

    /**
     * 一次性向所有桶申请 {@code permits} 个令牌，全成功或全不动。
     *
     * @throws QuotaUnavailableException Redis 不可用或返回异常结果时抛出，由调用方决定 fail-closed 行为
     */
    public AcquireResult acquire(List<BucketSpec> buckets, long nowMillis, int permits) {
        if (buckets.isEmpty()) {
            return AcquireResult.allow();
        }
        List<String> keys = new ArrayList<>(buckets.size());
        List<String> args = new ArrayList<>(2 + buckets.size() * 2);
        args.add(Long.toString(nowMillis));
        args.add(Integer.toString(permits));
        for (BucketSpec bucket : buckets) {
            keys.add(bucket.key());
            args.add(Double.toString(bucket.ratePerSecond()));
            args.add(Double.toString(bucket.burst()));
        }

        List<?> result;
        try {
            result = redisTemplate.execute(script, keys, args.toArray());
        } catch (RuntimeException e) {
            throw new QuotaUnavailableException("取令牌失败，Redis 不可用", e);
        }
        if (result == null || result.size() < 2) {
            throw new QuotaUnavailableException("取令牌脚本返回了非预期结果: " + result);
        }
        long grantedFlag = ((Number) result.get(0)).longValue();
        long waitMillis = ((Number) result.get(1)).longValue();
        return grantedFlag == 1L ? AcquireResult.allow() : AcquireResult.retryAfter(waitMillis);
    }
}
