package cn.bitoffer.msgcenter.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * LoggingAbortPolicyTest。
 *
 * @author LQH
 */
class LoggingAbortPolicyTest {

    @Test
    void rejectsByThrowingInsteadOfRunningOnTheCallerThread() {
        LoggingAbortPolicy policy = new LoggingAbortPolicy("test-pool");
        AtomicBoolean ranInline = new AtomicBoolean(false);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), policy);
        try {
            assertThatThrownBy(() -> policy.rejectedExecution(() -> ranInline.set(true), executor))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(ranInline).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void countsRejectionsSoTheyAreObservable() {
        LoggingAbortPolicy policy = new LoggingAbortPolicy("test-pool");
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), policy);
        try {
            for (int i = 0; i < 3; i++) {
                try {
                    policy.rejectedExecution(() -> { }, executor);
                }
                catch (RejectedExecutionException expected) {
                    // observed via counter below
                }
            }
            assertThat(policy.rejectionCount()).isEqualTo(3L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void saturatedBoundedExecutorRejectsExtraWorkWithoutBlockingSubmitter() throws Exception {
        LoggingAbortPolicy policy = new LoggingAbortPolicy("saturation");
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(1), policy);
        AtomicBoolean release = new AtomicBoolean(false);
        try {
            // occupy the single worker
            executor.execute(() -> {
                while (!release.get()) {
                    Thread.onSpinWait();
                }
            });
            executor.execute(() -> { }); // fills the queue (capacity 1)

            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(policy.rejectionCount()).isEqualTo(1L);
        } finally {
            release.set(true);
            executor.shutdownNow();
        }
    }
}
