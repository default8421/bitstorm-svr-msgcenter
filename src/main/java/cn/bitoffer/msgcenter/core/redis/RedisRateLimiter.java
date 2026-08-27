package cn.bitoffer.msgcenter.core.redis;

import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter whose counter increment and TTL are applied in a single, atomic Lua
 * script. Splitting {@code INCR} and {@code EXPIRE} into two round trips risks a key that never
 * expires if the process dies between the calls; the script closes that race.
 *
 * @author LQH
 */
@Component
@Slf4j
public class RedisRateLimiter {

    static final String INCREMENT_AND_EXPIRE_SCRIPT =
            "local current = redis.call('INCR', KEYS[1])\n"
            + "if current == 1 then\n"
            + "  redis.call('PEXPIRE', KEYS[1], ARGV[1])\n"
            + "end\n"
            + "return current";

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<Long> script;

    public RedisRateLimiter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(INCREMENT_AND_EXPIRE_SCRIPT, Long.class);
    }

    /**
     * Atomically increments the window counter and, on the first hit, sets its TTL.
     *
     * @return the current counter value, or a negative sentinel if Redis returned nothing.
     */
    public long incrementAndGet(String key, long ttlMillis) {
        Long current = redisTemplate.execute(script, Collections.singletonList(key),
                String.valueOf(ttlMillis));
        if (current == null) {
            log.error("rate-limit script returned no count for key {}", key);
            return -1L;
        }
        return current;
    }

    public boolean isWithinLimit(String key, int limit, long ttlMillis) {
        long current = incrementAndGet(key, ttlMillis);
        if (current < 0L) {
            return false;
        }
        return current <= limit;
    }
}
