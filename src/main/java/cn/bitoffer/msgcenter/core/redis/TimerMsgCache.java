package cn.bitoffer.msgcenter.core.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Clock;
import java.util.*;

/**
 * TimerMsgCache。
 *
 * @author LQH
 */
@Component
@Slf4j
public class TimerMsgCache {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Scheduled sender timestamps are epoch milliseconds everywhere else (the DB column and the
     * MySQL "on time" query both use {@code new Date().getTime()}). The ZSET score and the read
     * boundary must therefore also be milliseconds, otherwise members scored in millis would never
     * be selected by a seconds-based upper bound.
     */
    private Clock clock = Clock.systemUTC();

    public String GetCacheKey(){
        return "Timer_Msgs";
    }

    public void cacheSaveMsgTimePoint(long sendTimestampMillis){
        redisTemplate.opsForZSet().add(GetCacheKey(), sendTimestampMillis, sendTimestampMillis);
    }

    public List<String> getOnTimePointsFromCache(){
        try {
            return excuteScript(GetCacheKey(), 0L, clock.millis());
        } catch (Exception e) {
            log.error("failed to read due timer time points from cache", e);
            return null;
        }
    }

    private List<String> excuteScript(String key, Long field1, Long field2) {
        String script = "local elements = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[1], ARGV[2]) "+
                "for i, elem in ipairs(elements) do "+
                " redis.call('ZREM', KEYS[1], elem) "+
                "end "+
                "return elements ";

        return redisTemplate.execute(
                new DefaultRedisScript<List>(script, List.class),
                Collections.singletonList(key),  // KEYS[1]
                field1,                          // ARGV[1]
                field2                           // ARGV[2]
        );
    }

    void setRedisTemplate(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
