package cn.bitoffer.msgcenter.core.config;

import cn.bitoffer.msgcenter.core.retry.BackoffPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重试退避策略。
 *
 * @author LQH
 */
@Configuration
public class RetryConfig {

    /** Legacy MySQL/Kafka queue retries: 2s base, doubling, capped at 5 min, 20% jitter. */
    @Bean
    public BackoffPolicy legacyRetryBackoff() {
        return new BackoffPolicy(2_000L, 300_000L, 2.0, 0.2, 5);
    }

    /** 消息重试：1s 起步，翻倍，上限 5 分钟，带 20% 抖动。 */
    @Bean
    public BackoffPolicy notificationRetryBackoff() {
        return new BackoffPolicy(1_000L, 300_000L, 2.0, 0.2, 6);
    }
}
