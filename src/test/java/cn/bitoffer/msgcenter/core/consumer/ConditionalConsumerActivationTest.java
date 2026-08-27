package cn.bitoffer.msgcenter.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.bitoffer.common.redis.RedisBase;
import cn.bitoffer.common.redis.ReentrantDistributeLock;
import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.consumer.poll.MysqlMsgPollTask;
import cn.bitoffer.msgcenter.core.flowcontrol.BlockingDispatchGate;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContextResolver;
import cn.bitoffer.msgcenter.core.flowcontrol.FairDispatchScheduler;
import cn.bitoffer.msgcenter.core.flowcontrol.FlowControlProperties;
import cn.bitoffer.msgcenter.core.flowcontrol.RecipientFrequencyPolicy;
import cn.bitoffer.msgcenter.core.manager.DealMsgManager;
import cn.bitoffer.msgcenter.core.manager.SendMsgManager;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.retry.BackoffPolicy;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * ConditionalConsumerActivationTest。
 *
 * @author LQH
 */
class ConditionalConsumerActivationTest {

    private static final String[] SEND_MSG_CONF_PROPERTIES = {
            "send-msg-conf.open-cache=true",
            "send-msg-conf.max-retry-count=5",
            "send-msg-conf.email-account=account",
            "send-msg-conf.email-auth-code=code",
            "send-msg-conf.email-host=smtp.example.com",
            "send-msg-conf.email-port=587"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Collaborators.class)
            .withPropertyValues(SEND_MSG_CONF_PROPERTIES);

    @Test
    void enablesOnlyMysqlConsumerWhenMysqlIsUsedAsQueue() {
        contextRunner.withPropertyValues("send-msg-conf.mysql-as-mq=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MysqlMsgConsumer.class);
            assertThat(context).doesNotHaveBean(KafkaMsgConsumer.class);
        });
    }

    @Test
    void enablesOnlyKafkaConsumerWhenKafkaIsUsedAsQueue() {
        contextRunner.withPropertyValues("send-msg-conf.mysql-as-mq=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KafkaMsgConsumer.class);
            assertThat(context).doesNotHaveBean(MysqlMsgConsumer.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({MysqlMsgConsumer.class, KafkaMsgConsumer.class})
    static class Collaborators {
        @Bean MsgQueueMapper msgQueueMapper() { return mock(MsgQueueMapper.class); }
        @Bean MsgRecordMapper msgRecordMapper() { return mock(MsgRecordMapper.class); }
        @Bean MsgRecordService msgRecordService() { return mock(MsgRecordService.class); }
        @Bean MysqlMsgPollTask mysqlMsgPollTask() { return mock(MysqlMsgPollTask.class); }
        // MysqlMsgPollTask has an @Autowired BackoffPolicy field; Spring injects it even on the
        // mock, so the qualified bean must exist in this context.
        @Bean BackoffPolicy legacyRetryBackoff() { return BackoffPolicy.defaultPolicy(); }
        @Bean MysqlMsgClaimService mysqlMsgClaimService() {
            return mock(MysqlMsgClaimService.class);
        }
        @Bean DealMsgManager dealMsgManager() { return mock(DealMsgManager.class); }
        @Bean FairDispatchScheduler fairDispatchScheduler() {
            return new FairDispatchScheduler(new FlowControlProperties(), new SimpleMeterRegistry());
        }
        @Bean DispatchContextResolver dispatchContextResolver() {
            return mock(DispatchContextResolver.class);
        }
        @Bean RecipientFrequencyPolicy recipientFrequencyPolicy() {
            return mock(RecipientFrequencyPolicy.class);
        }
        @Bean BlockingDispatchGate blockingDispatchGate() {
            return mock(BlockingDispatchGate.class);
        }
        @Bean SendMsgManager sendMsgManager() { return mock(SendMsgManager.class); }
        @Bean SendMsgConf sendMsgConf() { return mock(SendMsgConf.class); }
        @Bean ReentrantDistributeLock reentrantDistributeLock() {
            return mock(ReentrantDistributeLock.class);
        }
        // RedisBase is final (not mockable on this Mockito version); a real instance is fine, its
        // RedisTemplate/StringRedisTemplate fields are satisfied by the mocks below.
        @Bean RedisBase redisBase() { return new RedisBase(); }
        @SuppressWarnings("unchecked")
        @Bean RedisTemplate<String, Object> redisTemplate() { return mock(RedisTemplate.class); }
        @Bean StringRedisTemplate stringRedisTemplate() { return mock(StringRedisTemplate.class); }
    }
}
