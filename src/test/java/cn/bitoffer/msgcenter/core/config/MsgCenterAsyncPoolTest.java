package cn.bitoffer.msgcenter.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * MsgCenterAsyncPoolTest。
 *
 * @author LQH
 */
class MsgCenterAsyncPoolTest {

    private final MsgCenterAsyncPool pool = new MsgCenterAsyncPool();

    @Test
    void timerExecutorIsBoundedNamedAndRejectsWithoutCallerRuns() {
        assertBoundedAbortingExecutor((ThreadPoolTaskExecutor) pool.timerMsgPoolExecutor(),
                "TimerMsg_");
    }

    @Test
    void mysqlDealExecutorIsBoundedNamedAndRejectsWithoutCallerRuns() {
        assertBoundedAbortingExecutor((ThreadPoolTaskExecutor) pool.dealMsgPoolExecutor(),
                "DealMsg_");
    }

    @Test
    void notificationExecutorIsBoundedNamedAndRejectsWithoutCallerRuns() {
        assertBoundedAbortingExecutor((ThreadPoolTaskExecutor) pool.notificationPoolExecutor(),
                "Notify_");
    }

    private void assertBoundedAbortingExecutor(ThreadPoolTaskExecutor executor, String prefix) {
        assertThat(executor.getThreadNamePrefix()).isEqualTo(prefix);
        ThreadPoolExecutor underlying = executor.getThreadPoolExecutor();

        // Queue must be bounded (not an unbounded LinkedBlockingQueue).
        int remaining = underlying.getQueue().remainingCapacity();
        assertThat(remaining).isPositive().isLessThan(Integer.MAX_VALUE);

        // Rejection must be observable and must never run the task on the scheduler/caller thread.
        assertThat(underlying.getRejectedExecutionHandler())
                .isInstanceOf(LoggingAbortPolicy.class);
        assertThat(underlying.getRejectedExecutionHandler())
                .isNotInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        executor.shutdown();
    }
}
