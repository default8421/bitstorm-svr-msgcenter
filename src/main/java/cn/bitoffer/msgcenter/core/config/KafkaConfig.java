package cn.bitoffer.msgcenter.core.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * kafka 模版：KafkaConfig
 * 配合消费者使用
 *
 *
 * @author LQH
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Autowired
    private KafkaProperties properties;

    /**
     * 创建一个新的消费者工厂
     * 创建多个工厂的时候 SpringBoot就不会自动帮忙创建工厂了；所以默认的还是自己创建一下
     * @return
     */
    @Bean
    public ConsumerFactory<Object, Object> kafkaConsumerFactory() {
        Map<String, Object> map =  properties.buildConsumerProperties();
        DefaultKafkaConsumerFactory<Object, Object> factory = new DefaultKafkaConsumerFactory<>( map);
        return factory;
    }

    /**
     * 创建一个新的消费者工厂
     * 但是修改为不自动提交
     *
     * @return
     */
    @Bean
    public ConsumerFactory<Object, Object> kafkaManualConsumerFactory() {
        Map<String, Object> map =  properties.buildConsumerProperties();
        map.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false);
        // Read from the earliest offset when a group has no committed offset. With the default
        // "latest", a consumer that subscribes before its topic is auto-created positions itself at
        // the current end once the topic appears, silently skipping any messages already produced
        // (the classic cold-start race). For a message center that must never drop, "earliest"
        // guarantees every produced record is delivered at least once.
        map.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // 一次只取一条。消费线程会在这条消息上同步等待发送配额，取一批的话整批的处理时间叠加起来
        // 很容易超过 max.poll.interval.ms，被 broker 判定为消费者卡死而反复 rebalance。
        map.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
        DefaultKafkaConsumerFactory<Object, Object> factory = new DefaultKafkaConsumerFactory<>( map);
        return factory;
    }


    /**
     * 手动提交的监听器工厂 (使用的消费组工厂必须 kafka.consumer.enable-auto-commit = false)
     * @return
     */
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<Integer, String>> kafkaManualAckListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<Integer, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kafkaManualConsumerFactory());
        //设置提交偏移量的方式 当Acknowledgment.acknowledge()侦听器调用该方法时，立即提交偏移量
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    /**
     * 监听器工厂 批量消费
     * @return
     */
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<Integer, String>> batchFactory() {
        ConcurrentKafkaListenerContainerFactory<Integer, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(kafkaConsumerFactory());
        factory.setBatchListener(true);
        return factory;
    }

    /**
     * 优先级主题生产者（低/中/高/重试）。acks=all 且开启幂等。
     *
     * @author LQH
     */
    @Bean
    public ProducerFactory<String, Object> legacyProducerFactory() {
        Map<String, Object> config = new HashMap<>(properties.buildProducerProperties());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> legacyKafkaTemplate(
            @Qualifier("legacyProducerFactory") ProducerFactory<String, Object> legacyProducerFactory) {
        return new KafkaTemplate<>(legacyProducerFactory);
    }
}
