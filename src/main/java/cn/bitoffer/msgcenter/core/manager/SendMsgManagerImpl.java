package cn.bitoffer.msgcenter.core.manager;

import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueTimerMapper;
import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import cn.bitoffer.msgcenter.core.model.MsgQueueTimerModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.redis.TimerMsgCache;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * SendMsgManagerImpl。
 *
 * @author LQH
 */
@Service
@Slf4j
public class SendMsgManagerImpl implements SendMsgManager{

    private static final long SEND_TIMEOUT_SECONDS = 10L;

    @Autowired
    MsgQueueMapper msgQueueMapper;

    @Autowired
    MsgQueueTimerMapper msgQueueTimerMapper;

    @Autowired
    @Qualifier("legacyKafkaTemplate")
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    TimerMsgCache timerMsgCache;

    @Override
    public String SendToMysql(SendMsgReq sendMsgReq) {
        MsgQueueModel newMsgModel = buildQueueModel(sendMsgReq);
        String tableName = queueTableName(sendMsgReq);

        // 入队成功才是“消息已被可靠受理”的提交点。写入失败绝不能吞掉异常再返回成功——那样调用方会
        // 以为消息已发送、实际永久丢失。这里必须把失败如实抛给上层，让接口返回错误，调用方据此重试
        // （至少一次投递语义）。
        try{
            msgQueueMapper.save(tableName,newMsgModel);
        }catch (Exception e){
            log.error("消息入队失败 tableName:{} msgId:{}", tableName, newMsgModel.getMsgId(), e);
            throw new BusinessException(ErrorCode.MSG_ENQUEUE_ERROR,
                    "消息入队失败 msgId:" + newMsgModel.getMsgId());
        }

        return  sendMsgReq.getMsgID();
    }

    /** Builds the MySQL-queue row model, generating a msgId when the caller did not supply one. */
    private MsgQueueModel buildQueueModel(SendMsgReq sendMsgReq) {
        if(StringUtils.isEmpty(sendMsgReq.getMsgID())) {
            sendMsgReq.setMsgID(UUID.randomUUID().toString());
        }
        MsgQueueModel newMsgModel = new MsgQueueModel();
        newMsgModel.setMsgId(sendMsgReq.getMsgID());
        newMsgModel.setSubject(sendMsgReq.getSubject());
        newMsgModel.setTo(sendMsgReq.getTo());
        newMsgModel.setPriority(sendMsgReq.getPriority());
        newMsgModel.setTemplateId(sendMsgReq.getTemplateId());
        newMsgModel.setTemplateData(JSONUtil.toJsonString(sendMsgReq.getTemplateData()));
        newMsgModel.setStatus(MsgStatus.Pending.getStatus());
        return newMsgModel;
    }

    /** Priority-based queue table name: low|middle|high|retry. */
    private String queueTableName(SendMsgReq sendMsgReq) {
        return Constants.TableNamePre_MsgQueue + PriorityEnum.GetPriorityStr(sendMsgReq.getPriority());
    }

    @Override
    public String SendToMq(SendMsgReq sendMsgReq) {
        // 1. 生成 MsgID
        if(StringUtils.isEmpty(sendMsgReq.getMsgID())) {
            sendMsgReq.setMsgID(UUID.randomUUID().toString());
        }

        // 2. 序列化请求为 一条 String 消息
        String mqData = JSONUtil.toJsonString(sendMsgReq);

        // 3.根据消息优先级，确定要投递的 Topic    low-topic|middel-topic|high-topic
        String topic =PriorityEnum.GetPriorityStr(sendMsgReq.getPriority())+Constants.Topic_Tail_MsgQueue;

        //4. 发送消息到消息队列中转；同步等待发送结果，确保 broker 已确认(acks=all)。
        //   发送失败必须抛出异常，让调用方(消费者)不提交偏移量以便重投，绝不吞异常。
        try {
            kafkaTemplate.send(topic, mqData).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("发送MQ被中断 msgId:" + sendMsgReq.getMsgID(), e);
        } catch (Exception e) {
            throw new IllegalStateException("发送MQ失败 msgId:" + sendMsgReq.getMsgID(), e);
        }

        // 5. 返回消息Id
        return  sendMsgReq.getMsgID();
    }

    @Override
    public String SendToTimer(SendMsgReq sendMsgReq) {
        // 生成消息 ID
        String msgId = UUID.randomUUID().toString();
        sendMsgReq.setMsgID(msgId);

        //序列化整个请求为 String
        String mqData = JSONUtil.toJsonString(sendMsgReq);

        // 构建MsgQueueTimerModel，数据库存入的参数模型
        MsgQueueTimerModel newMsgModel = new MsgQueueTimerModel();
        newMsgModel.setMsgId(msgId);
        newMsgModel.setReq(mqData);
        newMsgModel.setSendTimestamp(sendMsgReq.getSendTimestamp());
        newMsgModel.setStatus(MsgStatus.Pending.getStatus());

        // 存入数据库
        msgQueueTimerMapper.save(newMsgModel);

        // 时间点，存入 ZSET；
        timerMsgCache.cacheSaveMsgTimePoint(newMsgModel.getSendTimestamp());

        return msgId;
    }

}
