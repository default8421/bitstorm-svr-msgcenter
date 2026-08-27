package cn.bitoffer.msgcenter.core.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * RedisRateLimiterTest。
 *
 * @author LQH
 */
class RedisRateLimiterTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);

    @Test
    void luaScriptIncrementsAndExpiresAtomicallyInASingleRoundTrip() {
        assertThat(RedisRateLimiter.INCREMENT_AND_EXPIRE_SCRIPT)
                .contains("INCR")
                .contains("PEXPIRE")
                .contains("return");
        // The window key must only be given a TTL on the very first increment.
        assertThat(RedisRateLimiter.INCREMENT_AND_EXPIRE_SCRIPT).contains("== 1");
    }

    @Test
    void executesScriptExactlyOnceWithoutASeparateExpireCall() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(3L);

        limiter.incrementAndGet("window-key", 1_000L);

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(),
                eq("1000"));
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .isEqualTo(RedisRateLimiter.INCREMENT_AND_EXPIRE_SCRIPT);
        assertThat(keysCaptor.getValue()).containsExactly("window-key");
        verify(redisTemplate, never()).expire(any(), any());
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    void allowsRequestWhileCountIsWithinLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(5L);

        assertThat(limiter.isWithinLimit("k", 5, 1_000L)).isTrue();
    }

    @Test
    void rejectsRequestOnceCountExceedsLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(6L);

        assertThat(limiter.isWithinLimit("k", 5, 1_000L)).isFalse();
    }

    @Test
    void failsClosedWhenRedisReturnsNoCount() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(null);

        assertThat(limiter.isWithinLimit("k", 5, 1_000L)).isFalse();
        assertThat(limiter.incrementAndGet("k", 1_000L)).isNegative();
    }
}
