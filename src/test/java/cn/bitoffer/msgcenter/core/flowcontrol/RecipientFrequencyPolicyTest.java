package cn.bitoffer.msgcenter.core.flowcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.redis.RedisRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RecipientFrequencyPolicyTest。
 *
 * @author LQH
 */
class RecipientFrequencyPolicyTest {

    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final FlowControlProperties properties = new FlowControlProperties();
    private final RecipientFrequencyPolicy policy =
            new RecipientFrequencyPolicy(rateLimiter, properties, new SimpleMeterRegistry());

    @BeforeEach
    void enablePolicy() {
        properties.getSuppression().setEnabled(true);
        properties.getSuppression().setWindowSeconds(60);
        properties.getSuppression().setMaxPerWindow(3);
    }

    @Test
    void suppressesOnceARecipientHasHadEnoughForThisWindow() {
        when(rateLimiter.isWithinLimit(anyString(), anyInt(), anyLong())).thenReturn(false);

        assertThat(policy.shouldSuppress(context(PriorityEnum.PRIORITY_LOW), "t-1")).isTrue();
    }

    @Test
    void countsPerTenantRecipientChannelAndTemplate() {
        when(rateLimiter.isWithinLimit(anyString(), anyInt(), anyLong())).thenReturn(true);

        policy.shouldSuppress(context(PriorityEnum.PRIORITY_LOW), "t-1");

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(rateLimiter).isWithinLimit(key.capture(), anyInt(), anyLong());
        assertThat(key.getValue()).isEqualTo("XMSG_recipient_freq:acme:2:13800000000:t-1");
    }

    @Test
    void neverSuppressesHighPrioritySecurityMessages() {
        assertThat(policy.shouldSuppress(context(PriorityEnum.PRIORITY_HIGH), "t-1")).isFalse();
        // 高优先级压根不该去数频次，验证码不是营销。
        verify(rateLimiter, never()).isWithinLimit(anyString(), anyInt(), anyLong());
    }

    @Test
    void staysOutOfTheWayWhenTheFeatureIsOff() {
        properties.getSuppression().setEnabled(false);

        assertThat(policy.shouldSuppress(context(PriorityEnum.PRIORITY_LOW), "t-1")).isFalse();
        verify(rateLimiter, never()).isWithinLimit(anyString(), anyInt(), anyLong());
    }

    @Test
    void failsOpenWhenRedisIsUnavailable() {
        when(rateLimiter.isWithinLimit(anyString(), anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("redis down"));

        // 与配额相反：防打扰是体贴而非安全边界，Redis 抖动时不该反过来扣掉用户想要的通知。
        assertThat(policy.shouldSuppress(context(PriorityEnum.PRIORITY_LOW), "t-1")).isFalse();
    }

    private static DispatchContext context(PriorityEnum priority) {
        return new DispatchContext("acme", "billing", 2, priority.getPriorty(), "13800000000");
    }
}
