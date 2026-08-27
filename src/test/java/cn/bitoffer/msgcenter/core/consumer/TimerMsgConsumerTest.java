package cn.bitoffer.msgcenter.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.bitoffer.common.redis.ReentrantDistributeLock;
import cn.bitoffer.msgcenter.core.redis.TimerMsgCache;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TimerMsgConsumerTest。
 *
 * @author LQH
 */
class TimerMsgConsumerTest {

    private final ReentrantDistributeLock lock = mock(ReentrantDistributeLock.class);
    private final TimerMsgCache cache = mock(TimerMsgCache.class);

    private TimerMsgConsumer consumer(Clock clock) {
        TimerMsgConsumer consumer = new TimerMsgConsumer();
        consumer.reentrantDistributeLock = lock;
        consumer.timerMsgCache = cache;
        consumer.setClock(clock);
        return consumer;
    }

    @Test
    void doesNotBlockAndOnlyRetriesLeadershipAfterTheInterval() {
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
        when(lock.lockWithDog(anyString(), anyString(), anyLong())).thenReturn(false);
        TimerMsgConsumer consumer = consumer(fixed);

        // Two back-to-back ticks at the same instant: the second must be time-gated (no re-attempt),
        // and neither may block the scheduler thread.
        assertThatCode(() -> {
            consumer.consume();
            consumer.consume();
        }).doesNotThrowAnyException();

        verify(lock, times(1)).lockWithDog(anyString(), anyString(), anyLong());
    }

    @Test
    void consumesTimerMessagesOnceElectedLeader() {
        Clock fixed = Clock.fixed(Instant.ofEpochMilli(1_000_000L), ZoneOffset.UTC);
        when(lock.lockWithDog(anyString(), anyString(), anyLong())).thenReturn(true);
        when(cache.getOnTimePointsFromCache()).thenReturn(List.of());
        TimerMsgConsumer consumer = consumer(fixed);

        consumer.consume(); // acquires leadership
        consumer.consume(); // now leader -> reads due time points

        verify(cache).getOnTimePointsFromCache();
    }
}
