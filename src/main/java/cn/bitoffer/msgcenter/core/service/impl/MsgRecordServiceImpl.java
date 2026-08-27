package cn.bitoffer.msgcenter.core.service.impl;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContext;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;

/**
 * MsgRecordServiceImpl。
 *
 * @author LQH
 */
@Service
@Slf4j
public class MsgRecordServiceImpl implements MsgRecordService {

    /** 连投递上下文都解析不出来时的来源/租户占位（例如模板已删除）。 */
    private static final String UNKNOWN = "unknown";
    /** NOT NULL 文本列的兜底占位，避免因缺字段写不进终态记录。 */
    private static final String PLACEHOLDER = "-";

    @Autowired
    private MsgRecordMapper msgRecordMapper;

    @Autowired
    private SendMsgConf sendMsgConf;

    @Resource
    private RedisTemplate<String,String> redisTemplate;


    @Override
    public MsgRecordModel GetMsgRecordWithCache(String msgId) {
        return getMsgRecordWithCache(msgId);
    }

    @Override
    public boolean isAlreadySucceeded(String msgId) {
        // Read straight from MySQL, not the 30s cache: the cache may still hold a pre-push "no
        // record yet" miss, and trusting that here would let a redelivered message push twice.
        MsgRecordModel mr = msgRecordMapper.getMsgById(msgId);
        return mr != null && mr.getStatus() == MsgStatus.Succeed.getStatus();
    }

    @Override
    public void CreateOrUpdateMsgRecord(String msgId, SendMsgReq sendMsgReq, TemplateModel tp, MsgStatus status) {
        // 原子 upsert：一条 SQL 完成“没有就插入、有就更新状态”。谁先落到这个 msgId 谁就写全字段
        // （消费侧 Processing 一般是第一笔），后来者（Succeed 等）只翻状态——on duplicate 只更新
        // status，来源/渠道等不可变字段以首次写入为准。
        MsgRecordModel msgRd = buildRecord(msgId, sendMsgReq, tp, status);
        upsertAndEvict(msgRd, msgId);
    }

    @Override
    public void recordTerminalState(SendMsgReq sendMsgReq, DispatchContext ctx, MsgStatus status) {
        // 发生在 DealOneMsg 之外的终态：没有模板，用消费侧已解析出的 ctx 补齐来源/渠道/租户。ctx 为
        // null（连投递上下文都解析不出来）时退到占位来源，保证记录仍然产生。若该 msgId 已被 Processing
        // 写过，upsert 只会把状态翻成终态，首次写入的真实来源不受影响。
        String msgId = sendMsgReq.getMsgID();
        MsgRecordModel msgRd = new MsgRecordModel();
        msgRd.setMsgId(msgId);
        msgRd.setTenantId(ctx != null ? ctx.tenantId() : firstNonBlank(sendMsgReq.getTenantId(), UNKNOWN));
        msgRd.setSourceId(ctx != null ? ctx.sourceId() : UNKNOWN);
        msgRd.setChannel(ctx != null ? ctx.channel() : 0);
        msgRd.setSubject(firstNonBlank(sendMsgReq.getSubject(), PLACEHOLDER));
        msgRd.setTo(firstNonBlank(sendMsgReq.getTo(), ctx != null ? ctx.recipient() : PLACEHOLDER));
        msgRd.setTemplateId(firstNonBlank(sendMsgReq.getTemplateId(), PLACEHOLDER));
        msgRd.setTemplateData(JSONUtil.toJsonString(sendMsgReq.getTemplateData()));
        msgRd.setStatus(status.getStatus());
        upsertAndEvict(msgRd, msgId);
    }

    private void upsertAndEvict(MsgRecordModel msgRd, String msgId) {
        try{
            msgRecordMapper.upsertStatus(msgRd);
            // 写完立即失效读缓存：状态流转（如 Processing->Succeed）后，/msg/get_msg_record 立刻能读到
            // 最新状态，而不是等 30s TTL 过期还在返回旧状态。
            evictCache(msgId);
        }catch (Exception e){
            log.error("存储/更新消息发送记录失败(不影响投递,队列行才是投递依据) msgId:{}", msgId, e);
        }
    }

    public MsgRecordModel getMsgRecordWithCache(String msgId) {
        String msgRecordCacheKey = Constants.REDIS_KEY_MES_RECORD+msgId;
        String cacheMr = redisTemplate.opsForValue().get(msgRecordCacheKey);
        MsgRecordModel mr = null;
        if(!StringUtils.isEmpty(cacheMr) && sendMsgConf.isOpenCache()){
            mr = JSONUtil.parseObject(cacheMr,MsgRecordModel.class);
            if(mr != null){
                return mr;
            }
        }

        // 从数据库获取
        mr = msgRecordMapper.getMsgById(msgId);

        // 只缓存命中的记录：不把“查不到”缓存成 null，否则消息刚入库、审计还没落地的一瞬间查询会把
        // 空结果缓存 30s，导致记录已经写好了却仍旧查不到。
        if(mr != null){
            redisTemplate.opsForValue().set(msgRecordCacheKey,JSONUtil.toJsonString(mr), Duration.ofSeconds(30));
        }

        return mr;
    }

    private MsgRecordModel buildRecord(String msgId, SendMsgReq sendMsgReq, TemplateModel tp, MsgStatus status) {
        MsgRecordModel msgRd = new MsgRecordModel();
        msgRd.setMsgId(msgId);
        msgRd.setTenantId(resolveTenant(sendMsgReq, tp));
        msgRd.setTo(sendMsgReq.getTo());
        msgRd.setSubject(sendMsgReq.getSubject());
        msgRd.setTemplateId(sendMsgReq.getTemplateId());
        msgRd.setTemplateData(JSONUtil.toJsonString(sendMsgReq.getTemplateData()));
        msgRd.setSourceId(tp.getSourceId());
        msgRd.setChannel(tp.getChannel());
        msgRd.setStatus(status.getStatus());
        return msgRd;
    }

    private void evictCache(String msgId) {
        try {
            redisTemplate.delete(Constants.REDIS_KEY_MES_RECORD + msgId);
        } catch (Exception e) {
            log.warn("失效消息记录缓存失败(不影响投递) msgId:{}", msgId, e);
        }
    }

    private static String resolveTenant(SendMsgReq sendMsgReq, TemplateModel tp) {
        if (sendMsgReq != null && StringUtils.isNotBlank(sendMsgReq.getTenantId())) {
            return sendMsgReq.getTenantId();
        }
        return tp == null ? null : tp.getTenantId();
    }

    private static String firstNonBlank(String value, String fallback) {
        return StringUtils.isNotBlank(value) ? value : fallback;
    }
}
