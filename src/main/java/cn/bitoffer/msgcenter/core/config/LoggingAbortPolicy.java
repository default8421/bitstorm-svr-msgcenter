package cn.bitoffer.msgcenter.core.config;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Rejection handler that makes back-pressure observable and safe.
 *
 * <p>Unlike {@link ThreadPoolExecutor.CallerRunsPolicy}, it never runs the task on the submitting
 * thread. Running work inline on a {@code @Scheduled}/poller thread would stall the scheduler and
 * silently defeat the bounded queue. Instead a rejection is counted, logged and surfaced as a
 * {@link RejectedExecutionException}; callers keep the message in its source (DB {@code PROCESSING}
 * row / uncommitted Kafka offset) so it is retried rather than lost.
 *
 * @author LQH
 */
@Slf4j
public class LoggingAbortPolicy implements RejectedExecutionHandler {

    private final String poolName;
    private final AtomicLong rejections = new AtomicLong();

    public LoggingAbortPolicy(String poolName) {
        this.poolName = poolName;
    }

    public long rejectionCount() {
        return rejections.get();
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        long total = rejections.incrementAndGet();
        log.warn("thread pool [{}] rejected a task (total rejections={}, queueSize={}, active={}); "
                + "message will be retried from its source", poolName, total,
                executor.getQueue().size(), executor.getActiveCount());
        throw new RejectedExecutionException(
                "Task " + task + " rejected from pool " + poolName);
    }
}
