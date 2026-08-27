package cn.bitoffer.msgcenter.core.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.flowcontrol.AcquireResult;
import cn.bitoffer.msgcenter.core.flowcontrol.BucketSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * RedisTokenBucketLimiterTest。
 *
 * @author LQH
 */
class RedisTokenBucketLimiterTest {

    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redisTemplate = mock(RedisTemplate.class);
    private final RedisTokenBucketLimiter limiter = new RedisTokenBucketLimiter(redisTemplate);

    @Test
    void scriptChecksEveryBucketBeforeDeductingFromAnyOfThem() {
        String script = RedisTokenBucketLimiter.ACQUIRE_SCRIPT;
        int checkLoop = script.indexOf("if tokens < permits then");
        int earlyReturn = script.indexOf("if wait > 0 then");
        int deductLoop = script.indexOf("HMSET");

        // 扣减必须整体发生在"所有桶都够"的判断之后，否则平台桶扣成功、渠道桶失败时会白吃令牌。
        assertThat(checkLoop).isGreaterThan(0);
        assertThat(earlyReturn).isGreaterThan(checkLoop);
        assertThat(deductLoop).isGreaterThan(earlyReturn);
    }

    @Test
    void passesEachBucketRateAndBurstPositionallyAfterNowAndPermits() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(Arrays.asList(1L, 0L));

        limiter.acquire(Arrays.asList(new BucketSpec("platform", 100, 200),
                new BucketSpec("channel", 20, 40)), 1_700_000_000_000L, 1);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly("platform", "channel");
        assertThat(args.getAllValues()).containsExactly("1700000000000", "1", "100.0", "200.0",
                "20.0", "40.0");
    }

    @Test
    void reportsHowLongToWaitWhenABucketIsEmpty() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(Arrays.asList(0L, 250L));

        AcquireResult result = limiter.acquire(
                Collections.singletonList(new BucketSpec("platform", 4, 4)), 1L, 1);

        assertThat(result.granted()).isFalse();
        assertThat(result.retryAfterMillis()).isEqualTo(250L);
    }

    @Test
    void grantsWithoutTouchingRedisWhenNoBucketApplies() {
        AcquireResult result = limiter.acquire(Collections.emptyList(), 1L, 1);

        assertThat(result.granted()).isTrue();
    }

    @Test
    void surfacesRedisOutageSoCallersCanFailClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> limiter.acquire(
                Collections.singletonList(new BucketSpec("platform", 1, 1)), 1L, 1))
                .isInstanceOf(QuotaUnavailableException.class);
    }

    @Test
    void treatsAnUnexpectedScriptResultAsAnOutageRatherThanAGrant() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(Collections.singletonList(1L));

        assertThatThrownBy(() -> limiter.acquire(
                Collections.singletonList(new BucketSpec("platform", 1, 1)), 1L, 1))
                .isInstanceOf(QuotaUnavailableException.class);
    }
}
