package cn.bitoffer.msgcenter.core.flowcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.exception.OverloadException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * AdmissionGuardTest。
 *
 * @author LQH
 */
class AdmissionGuardTest {

    private final FlowControlProperties properties = new FlowControlProperties();
    private BacklogSnapshot snapshot = new BacklogSnapshot(0L, 0L, true);

    private final AdmissionGuard guard = new AdmissionGuard(
            provider(() -> snapshot), properties, new SimpleMeterRegistry());

    @Test
    void admitsEverythingAtNormalWatermark() {
        snapshot = new BacklogSnapshot(10L, 0L, true);

        assertThatCode(() -> guard.check(PriorityEnum.PRIORITY_LOW.getPriorty()))
                .doesNotThrowAnyException();
    }

    @Test
    void shedsLowPriorityFirstAtTheSoftWatermark() {
        properties.getAdmission().setSoftWatermark(100L);
        properties.getAdmission().setHardWatermark(1_000L);
        snapshot = new BacklogSnapshot(150L, 0L, true);

        assertThatThrownBy(() -> guard.check(PriorityEnum.PRIORITY_LOW.getPriorty()))
                .isInstanceOf(OverloadException.class);
        assertThatCode(() -> guard.check(PriorityEnum.PRIORITY_MIDDLE.getPriorty()))
                .doesNotThrowAnyException();
    }

    @Test
    void keepsAcceptingHighPriorityEvenPastTheHardWatermark() {
        properties.getAdmission().setHardWatermark(100L);
        snapshot = new BacklogSnapshot(100_000L, 0L, true);

        assertThatThrownBy(() -> guard.check(PriorityEnum.PRIORITY_MIDDLE.getPriorty()))
                .isInstanceOf(OverloadException.class);
        // 验证码和告警只要还写得进队列就必须收下。
        assertThatCode(() -> guard.check(PriorityEnum.PRIORITY_HIGH.getPriorty()))
                .doesNotThrowAnyException();
    }

    @Test
    void treatsAStalledQueueAsOverloadEvenWhenItIsShort() {
        properties.getAdmission().setSoftWatermark(100_000L);
        properties.getAdmission().setHardWatermark(100_000L);
        properties.getAdmission().setMaxOldestAgeSeconds(60L);
        snapshot = new BacklogSnapshot(5L, 120_000L, true);

        // 队列很短但完全没人消费，只看积压量会漏掉这种情况。
        assertThatThrownBy(() -> guard.check(PriorityEnum.PRIORITY_MIDDLE.getPriorty()))
                .isInstanceOf(OverloadException.class);
    }

    @Test
    void tellsTheCallerHowLongToBackOff() {
        properties.getAdmission().setSoftWatermark(1L);
        properties.getAdmission().setRetryAfterSeconds(7);
        snapshot = new BacklogSnapshot(10L, 0L, true);

        assertThatThrownBy(() -> guard.check(PriorityEnum.PRIORITY_LOW.getPriorty()))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories
                        .type(OverloadException.class))
                .satisfies(e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(7));
    }

    @Test
    void admitsWhenTheBacklogSignalIsUnavailableRatherThanGuessing() {
        properties.getAdmission().setSoftWatermark(1L);
        snapshot = BacklogSnapshot.unknown();

        assertThatCode(() -> guard.check(PriorityEnum.PRIORITY_LOW.getPriorty()))
                .doesNotThrowAnyException();
    }

    @Test
    void admitsEverythingWhenTheGuardIsTurnedOff() {
        properties.getAdmission().setEnabled(false);
        properties.getAdmission().setSoftWatermark(1L);
        snapshot = new BacklogSnapshot(999_999L, 0L, true);

        assertThatCode(() -> guard.check(PriorityEnum.PRIORITY_LOW.getPriorty()))
                .doesNotThrowAnyException();
    }

    private static ObjectProvider<BacklogSnapshotProvider> provider(
            BacklogSnapshotProvider delegate) {
        return new ObjectProvider<>() {
            @Override
            public BacklogSnapshotProvider getObject(Object... args) {
                return delegate;
            }

            @Override
            public BacklogSnapshotProvider getObject() {
                return delegate;
            }

            @Override
            public BacklogSnapshotProvider getIfAvailable() {
                return delegate;
            }

            @Override
            public BacklogSnapshotProvider getIfUnique() {
                return delegate;
            }
        };
    }
}
