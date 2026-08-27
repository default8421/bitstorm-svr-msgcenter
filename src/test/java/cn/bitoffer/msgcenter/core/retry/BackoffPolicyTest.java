package cn.bitoffer.msgcenter.core.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * BackoffPolicyTest。
 *
 * @author LQH
 */
class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(1_000L, 60_000L, 2.0, 0.2, 5);

    @Test
    void growsExponentiallyPerAttemptWhenJitterIsNeutral() {
        // randomFraction 0.5 keeps the symmetric jitter centred on the raw backoff value.
        assertThat(policy.delayMillis(1, 0.5)).isEqualTo(1_000L);
        assertThat(policy.delayMillis(2, 0.5)).isEqualTo(2_000L);
        assertThat(policy.delayMillis(3, 0.5)).isEqualTo(4_000L);
        assertThat(policy.delayMillis(4, 0.5)).isEqualTo(8_000L);
    }

    @Test
    void capsRawBackoffAtMaxDelay() {
        assertThat(policy.delayMillis(50, 0.5)).isEqualTo(60_000L);
    }

    @Test
    void keepsJitteredDelayWithinBounds() {
        long floor = policy.delayMillis(3, 0.0);
        long ceiling = policy.delayMillis(3, 0.999999);
        assertThat(floor).isEqualTo(3_200L); // 4000 * (1 - 0.2)
        assertThat(ceiling).isBetween(4_780L, 4_800L); // ~4000 * (1 + 0.2)
        assertThat(floor).isLessThan(policy.delayMillis(3, 0.5));
        assertThat(ceiling).isGreaterThan(policy.delayMillis(3, 0.5));
    }

    @Test
    void treatsAttemptsBelowOneAsFirstAttempt() {
        assertThat(policy.delayMillis(0, 0.5)).isEqualTo(1_000L);
        assertThat(policy.delayMillis(-3, 0.5)).isEqualTo(1_000L);
    }

    @Test
    void reportsExhaustionOnceAttemptsReachTheCap() {
        assertThat(policy.isExhausted(4)).isFalse();
        assertThat(policy.isExhausted(5)).isTrue();
        assertThat(policy.isExhausted(6)).isTrue();
        assertThat(policy.maxAttempts()).isEqualTo(5);
    }

    @Test
    void computesNextAttemptInstantFromDelay() {
        Instant now = Instant.parse("2026-08-13T10:00:00Z");
        assertThat(policy.nextAttemptAt(now, 2, 0.5)).isEqualTo(now.plusMillis(2_000L));
    }

    @Test
    void neverExceedsMaxDelayEvenWithUpperJitter() {
        assertThat(policy.delayMillis(50, 0.999999)).isLessThanOrEqualTo(60_000L);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new BackoffPolicy(0L, 10L, 2.0, 0.1, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(10L, 5L, 2.0, 0.1, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(10L, 100L, 0.9, 0.1, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(10L, 100L, 2.0, -0.1, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(10L, 100L, 2.0, 1.5, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(10L, 100L, 2.0, 0.1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
