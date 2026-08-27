package cn.bitoffer.msgcenter.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Dedicated {@link ThreadPoolTaskScheduler} for all {@code @Scheduled} work.
 *
 * <p>The Spring default single-threaded scheduler means one slow/blocking job stalls every other
 * scheduled task. Here we provide a multi-threaded scheduler (>= 4 threads) with:
 * <ul>
 *   <li>identifiable thread names,</li>
 *   <li>an error handler so a thrown task never kills its worker thread,</li>
 *   <li>graceful shutdown that drains in-flight tasks.</li>
 * </ul>
 * With this in place, scheduled methods must never call {@code Thread.sleep} to "wait" — blocking a
 * scheduler thread wastes a pool slot; time-based gating is used instead.
 *
 * @author LQH
 */
@Configuration
@Slf4j
public class SchedulingConfig implements SchedulingConfigurer {

    private static final int MIN_POOL_SIZE = 4;
    private static final int AWAIT_TERMINATION_SECONDS = 30;

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(MIN_POOL_SIZE, Runtime.getRuntime().availableProcessors()));
        scheduler.setThreadNamePrefix("msgcenter-sched-");
        scheduler.setErrorHandler(throwable ->
                log.error("scheduled task threw an exception; scheduler thread preserved", throwable));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(taskScheduler());
    }
}
