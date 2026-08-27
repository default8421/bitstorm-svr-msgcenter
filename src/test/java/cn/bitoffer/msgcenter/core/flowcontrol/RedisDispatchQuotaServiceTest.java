package cn.bitoffer.msgcenter.core.flowcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.FlowControlProperties.Limit;
import cn.bitoffer.msgcenter.core.redis.RedisTokenBucketLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RedisDispatchQuotaServiceTest。
 *
 * @author LQH
 */
class RedisDispatchQuotaServiceTest {

    private final RedisTokenBucketLimiter limiter = mock(RedisTokenBucketLimiter.class);
    private final QuotaCatalog catalog = mock(QuotaCatalog.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RedisDispatchQuotaService quotaService =
            new RedisDispatchQuotaService(limiter, catalog, meterRegistry);

    @BeforeEach
    void stubLimits() {
        when(catalog.platformLimit()).thenReturn(new Limit(1000, 2000));
        when(catalog.channelLimit(anyInt())).thenReturn(new Limit(50, 100));
        when(catalog.tenantLimit(any())).thenReturn(new Limit(10, 20));
        when(limiter.acquire(anyList(), anyLong(), anyInt())).thenReturn(AcquireResult.allow());
    }

    @Test
    void chargesPlatformChannelAndTenantBucketsForOrdinaryTraffic() {
        quotaService.acquire(context(PriorityEnum.PRIORITY_LOW.getPriorty()));

        assertThat(capturedBucketKeys()).containsExactly(
                "XMSG_bucket:platform", "XMSG_bucket:ch:2", "XMSG_bucket:tn:acme:billing:2");
    }

    @Test
    void letsHighPriorityBorrowTenantShareButNeverTheChannelHardLimit() {
        quotaService.acquire(context(PriorityEnum.PRIORITY_HIGH.getPriorty()));

        // 验证码、告警可以越过租户额度，但供应商合同和中台自身承载能力不能越过。
        assertThat(capturedBucketKeys())
                .containsExactly("XMSG_bucket:platform", "XMSG_bucket:ch:2");
    }

    @Test
    void keepsAZeroQpsBucketSoOperatorsCanHardStopAChannel() {
        when(catalog.channelLimit(anyInt())).thenReturn(new Limit(0, 0));

        quotaService.acquire(context(PriorityEnum.PRIORITY_LOW.getPriorty()));

        assertThat(capturedBuckets())
                .anySatisfy(bucket -> assertThat(bucket.ratePerSecond()).isZero());
    }

    @Test
    void countsThrottledDecisionsSeparatelyFromGrantedOnes() {
        when(limiter.acquire(anyList(), anyLong(), anyInt()))
                .thenReturn(AcquireResult.retryAfter(120L));

        AcquireResult result = quotaService.acquire(context(PriorityEnum.PRIORITY_LOW.getPriorty()));

        assertThat(result.granted()).isFalse();
        assertThat(meterRegistry.get("msgcenter.dispatch.quota")
                .tag("result", "throttled").counter().count()).isEqualTo(1.0);
    }

    private static DispatchContext context(int priority) {
        return new DispatchContext("acme", "billing", 2, priority, "13800000000");
    }

    @SuppressWarnings("unchecked")
    private List<BucketSpec> capturedBuckets() {
        ArgumentCaptor<List<BucketSpec>> captor = ArgumentCaptor.forClass(List.class);
        verify(limiter).acquire(captor.capture(), anyLong(), anyInt());
        return captor.getValue();
    }

    private List<String> capturedBucketKeys() {
        return capturedBuckets().stream().map(BucketSpec::key).toList();
    }
}
