package cn.bitoffer.msgcenter.core.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * TimerMsgCacheTest。
 *
 * @author LQH
 */
class TimerMsgCacheTest {

    private static final long NOW_MILLIS = 1_700_000_000_000L;

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, Object> zSetOperations = mock(ZSetOperations.class);

    private TimerMsgCache newCache() {
        TimerMsgCache cache = new TimerMsgCache();
        cache.setRedisTemplate(redisTemplate);
        cache.setClock(Clock.fixed(Instant.ofEpochMilli(NOW_MILLIS), ZoneOffset.UTC));
        return cache;
    }

    @Test
    void storesTimePointScoreInMillisWithoutRescaling() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        TimerMsgCache cache = newCache();

        cache.cacheSaveMsgTimePoint(NOW_MILLIS);

        ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);
        verify(zSetOperations).add(eq(cache.GetCacheKey()), eq(NOW_MILLIS), score.capture());
        assertThat(score.getValue()).isEqualTo((double) NOW_MILLIS);
    }

    @Test
    void queriesDueTimePointsUsingMillisBoundaryConsistentWithStoredScores() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenReturn(List.of(String.valueOf(NOW_MILLIS)));
        TimerMsgCache cache = newCache();

        List<String> due = cache.getOnTimePointsFromCache();

        assertThat(due).containsExactly(String.valueOf(NOW_MILLIS));
        // The upper bound must be "now" in millis (NOT seconds); previously it divided by 1000,
        // so millisecond-scored members would never be collected.
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq(0L), eq(NOW_MILLIS));
    }
}
