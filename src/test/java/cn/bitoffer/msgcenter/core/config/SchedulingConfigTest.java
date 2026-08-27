package cn.bitoffer.msgcenter.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * SchedulingConfigTest。
 *
 * @author LQH
 */
class SchedulingConfigTest {

    private final SchedulingConfig config = new SchedulingConfig();

    @Test
    void providesADedicatedNamedSchedulerWithAtLeastFourThreads() {
        ThreadPoolTaskScheduler scheduler = config.taskScheduler();
        try {
            assertThat(scheduler.getThreadNamePrefix()).isEqualTo("msgcenter-sched-");
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .isGreaterThanOrEqualTo(4);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void schedulerWaitsForTasksAndKeepsRunningAfterATaskThrows() throws Exception {
        ThreadPoolTaskScheduler scheduler = config.taskScheduler();
        try {
            // An error handler must swallow/log task exceptions so one bad run never kills the
            // scheduler thread. Without it, the executor would surface the exception.
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(2);
            scheduler.execute(() -> {
                latch.countDown();
                throw new RuntimeException("boom");
            });
            scheduler.execute(latch::countDown);
            assertThat(latch.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }
}
