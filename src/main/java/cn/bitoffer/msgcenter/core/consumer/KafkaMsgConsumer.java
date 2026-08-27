package cn.bitoffer.msgcenter.core.consumer;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.BlockingDispatchGate;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContext;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContextResolver;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchThrottledException;
import cn.bitoffer.msgcenter.core.flowcontrol.RecipientFrequencyPolicy;
import cn.bitoffer.msgcenter.core.manager.DealMsgManager;
import cn.bitoffer.msgcenter.core.manager.SendMsgManager;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Legacy Kafka consumer.
 *
 * <p>Only enabled when {@code send-msg-conf.mysql-as-mq=false}; when MySQL is used as the queue the
 * MySQL consumer path is active instead, so the two consumers never idle-spin side by side.
 *
 * <p>Acknowledgement is offset-safe: the record is only committed once processing succeeded, or the
 * failed message has been durably re-queued to the retry topic / marked as a final (DEAD) failure.
 * If re-queuing itself fails the exception propagates and the offset is left uncommitted so Kafka
 * redelivers the record — messages are never silently dropped.
 *
 * @author LQH
 */
@Component
@ConditionalOnProperty(prefix = "send-msg-conf", name = "mysql-as-mq", havingValue = "false")
@Slf4j
public class KafkaMsgConsumer {
    @Autowired
    DealMsgManager dealMsgManager;

    @Autowired
    MsgRecordMapper msgRecordMapper;

    @Autowired
    MsgRecordService msgRecordService;

    @Autowired
    MsgQueueMapper msgQueueMapper;

    @Autowired
    SendMsgConf sendMsgConf;

    @Autowired
    SendMsgManager  sendMsgManager;

    @Autowired
    DispatchContextResolver dispatchContextResolver;

    @Autowired
    RecipientFrequencyPolicy recipientFrequencyPolicy;

    @Autowired
    BlockingDispatchGate blockingDispatchGate;

    @KafkaListener(topics = "low-topic", groupId = "TEST_GROUP",concurrency = "1", containerFactory = "kafkaManualAckListenerContainerFactory")
    public void consumeLow(ConsumerRecord<?, ?> record, Acknowledgment ack, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        handleMQMsg(record,ack,topic);
    }

    @KafkaListener(topics = "middle-topic", groupId = "TEST_GROUP",concurrency = "3", containerFactory = "kafkaManualAckListenerContainerFactory")
    public void consumeMiddle(ConsumerRecord<?, ?> record, Acknowledgment ack, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        handleMQMsg(record,ack,topic);
    }

    @KafkaListener(topics = "high-topic", groupId = "TEST_GROUP",concurrency = "6", containerFactory = "kafkaManualAckListenerContainerFactory")
    public void consumeHigh(ConsumerRecord<?, ?> record, Acknowledgment ack, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        handleMQMsg(record,ack,topic);
    }

    @KafkaListener(topics = "retry-topic", groupId = "TEST_GROUP",concurrency = "1", containerFactory = "kafkaManualAckListenerContainerFactory")
    public void consumeRetry(ConsumerRecord<?, ?> record, Acknowledgment ack, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        handleMQMsg(record,ack,topic);
    }

    private void handleMQMsg(ConsumerRecord<?, ?> record, Acknowledgment ack, String topic){
        Object value = record == null ? null : record.value();
        if (value == null) {
            // Nothing actionable in an empty record; commit so the consumer advances.
            log.warn("Kafka收到空消息, 跳过. Topic:{}", topic);
            ack.acknowledge();
            return;
        }

        SendMsgReq req = JSONUtil.parseObject(value.toString(), SendMsgReq.class);
        if (req == null) {
            // Poison / unparseable payload: retrying will never help, so commit to avoid a hot loop.
            log.error("Kafka消息无法解析, 跳过毒消息. Topic:{},Message:{}", topic, value);
            ack.acknowledge();
            return;
        }

        DispatchContext ctx = dispatchContextResolver.resolve(req);

        // 防打扰命中：业务上就不该发，落 SUPPRESSED 终态后提交 offset，不进重试。这条消息不会进入
        // DealOneMsg，所以由这里用 ctx 补齐来源/渠道把投影记录建出来（否则查询面板看不到它）。
        if (recipientFrequencyPolicy.shouldSuppress(ctx, req.getTemplateId())) {
            msgRecordService.recordTerminalState(req, ctx, MsgStatus.Suppressed);
            ack.acknowledge();
            return;
        }

        // 配额不足时不提交 offset 并抛出：容器会 seek 回当前位置稍后重投。限流不算投递失败，
        // 所以既不增加 retryCount，也不转发到 retry-topic。
        if (!blockingDispatchGate.awaitPermit(ctx)) {
            throw new DispatchThrottledException(
                    "等待发送配额超时，消息保留在 Kafka 稍后重投 msgId:" + req.getMsgID());
        }

        try {
            dealMsgManager.DealOneMsg(req);
            log.info("Kafka消费成功! Topic:{},MsgId:{}", topic, req.getMsgID());
        } catch (Exception processingError) {
            // Durably route the failure before committing. If routing throws, the exception
            // propagates and the offset is NOT committed, so Kafka will redeliver.
            handleMqRetryAfterFailure(req);
            log.error("Kafka消费失败, 已进入重试/终态处理. Topic:{},MsgId:{}", topic, req.getMsgID(),
                    processingError);
        }
        ack.acknowledge();
    }



    private void handleMqRetryAfterFailure(SendMsgReq req){
        // 增加重试次数并检查是否达到上限；消息记录可能尚未落库，需容忍为空避免 NPE
        MsgRecordModel mrd = msgRecordMapper.getMsgById(req.getMsgID());
        int currentRetry = mrd == null ? 0 : mrd.getRetryCount();

        if(currentRetry >= sendMsgConf.getMaxRetryCount()){
            log.info("消息{}已达到最大重试次数，标记为最终失败(DEAD):{}", req.getMsgID(),
                    sendMsgConf.getMaxRetryCount());
            // 更新消息状态为最终失败（终态，持久化后即可安全提交偏移量）
            msgRecordMapper.setStatus(req.getMsgID(), MsgStatus.Failed.getStatus());
            return;
        }
        // 增加重试次数
        int newCount = currentRetry + 1;
        msgRecordMapper.incrementRetryCount(req.getMsgID(),newCount);

        // 重新发送消息到重试队列；SendToMq 同步等待发送结果，失败会抛异常并阻止提交偏移量
        req.setPriority(PriorityEnum.PRIORITY_RETRY.getPriorty());
        sendMsgManager.SendToMq(req);
    }

}
