package cn.bitoffer.msgcenter.core.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步线程池：审计写入、消息消费各自隔离，有界队列，拒绝策略打日志。
 *
 * @author LQH
 */
@Configuration
@EnableAsync
@Slf4j
public class MsgCenterAsyncPool {

    private static final int AWAIT_TERMINATION_SECONDS = 30;

    private ThreadPoolTaskExecutor build(String name, int core, int max, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(name);
        executor.setRejectedExecutionHandler(new LoggingAbortPolicy(name));
        // Graceful shutdown: stop accepting, then drain in-flight tasks within the timeout.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        log.info("initialized thread pool {} core={} max={} queue={}", name, core, max,
                queueCapacity);
        return executor;
    }

    /** Timer/scheduled message resend workload. */
    @Bean(name = "timerMsgPoll")
    public Executor timerMsgPoolExecutor() {
        return build("TimerMsg_", 10, 20, 1000);
    }

    /** Legacy MySQL-queue message dispatch workload (matching + send). */
    @Bean(name = "mysqlMsgDealPoll")
    public Executor dealMsgPoolExecutor() {
        return build("DealMsg_", 10, 20, 5000);
    }

    /** 通知类异步工作负载线程池，与消费线程池隔离。 */
    @Bean(name = "notificationExecutor")
    public Executor notificationPoolExecutor() {
        return build("Notify_", 4, 8, 500);
    }
}
