package cn.bitoffer.msgcenter.core.manager;

import cn.bitoffer.msgcenter.core.enums.ChannelEnum;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.mapper.TemplateMapper;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.msgpush.MsgPushService;
import cn.bitoffer.msgcenter.core.msgpush.base.ChannelMsgBase;
import cn.bitoffer.msgcenter.core.msgpush.channel.EmailServiceImpl;
import cn.bitoffer.msgcenter.core.msgpush.channel.LarkServiceImpl;
import cn.bitoffer.msgcenter.core.msgpush.channel.SMSServiceImpl;
import cn.bitoffer.msgcenter.core.service.TemplateService;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import cn.bitoffer.msgcenter.core.utils.TemplateRenderer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * DealMsgManagerImpl。
 *
 * @author LQH
 */
@Service
@Slf4j
public class DealMsgManagerImpl implements DealMsgManager{

    public static Map<Integer, MsgPushService> channelStrategyMap = new HashMap<>();

    @Autowired
    MsgPushService emailServiceImpl;
    @Autowired
    MsgPushService larkServiceImpl;
    @Autowired
    MsgPushService SMSServiceImpl;

    // 初始化各种推送策略服务 Email|Lark|SMS
    @PostConstruct
    public void initChannelStrategyMap() {
        channelStrategyMap.put(ChannelEnum.Channel_EMAIL.getChannel(), emailServiceImpl);
        channelStrategyMap.put(ChannelEnum.Channel_LARK.getChannel(), larkServiceImpl);
        channelStrategyMap.put(ChannelEnum.Channel_SMS.getChannel(), SMSServiceImpl);
    }
    @Autowired
    TemplateService templateService;

    @Autowired
    MsgRecordService msgRecordService;

    @Autowired
    MeterRegistry meterRegistry;

    @Override
    public void DealOneMsg(SendMsgReq sendMsgReq) {

        // 0. 幂等消费：发送前先查记录表，已经成功推送过的 msgId 直接跳过，不再重复推送。
        //    这一步专门用来防止 broker 的 at-least-once 重投（比如 Kafka 在真实推送成功、
        //    offset 还没提交前进程崩溃，导致同一条消息被重新投递）变成对用户的重复真实发送。
        if (msgRecordService.isAlreadySucceeded(sendMsgReq.getMsgID())) {
            log.info("消息{}已成功推送过，跳过重复推送(幂等)", sendMsgReq.getMsgID());
            return;
        }

        // 1. 查找模板
        TemplateModel tp = templateService.GetTemplateWithCache(sendMsgReq.getTemplateId());

        // 2.替换模板中的变量；未填写的 ${var} 替换为空，避免原文泄漏到用户侧。
        String msgContent = TemplateRenderer.render(tp.getContent(), sendMsgReq.getTemplateData());

        // 3. 构建推送消息的基本参数
        ChannelMsgBase base = new ChannelMsgBase();
        base.setTo(sendMsgReq.getTo());
        String subject = sendMsgReq.getSubject();
        if (StringUtils.isBlank(subject)) {
            subject = tp.getSubject();
        }
        base.setSubject(subject);
        base.setContent(msgContent);
        base.setPriority(sendMsgReq.getPriority());
        base.setTemplateId(sendMsgReq.getTemplateId());
        base.setTemplateData(sendMsgReq.getTemplateData());

        // 4. 根据渠道，获取具体的推送策略 Email|Lark|SMS
        MsgPushService msgService = channelStrategyMap.get(tp.getChannel());

        // 4.5 推送前写入投影：置为 Processing。入口不再写记录，这是该 msgId 的第一笔写入，负责落全字段
        //     （来源/渠道/租户/收件人等）。它同时保证了记录行一定存在，后续重试计数、终态更新都有行可改。
        msgRecordService.CreateOrUpdateMsgRecord(sendMsgReq.getMsgID(), sendMsgReq, tp, MsgStatus.Processiong);

        // 5. 调用具体策略服务去推送消息，用 Timer 记录下游耗时和成功/失败，用于监控每个渠道的
        //    健康度（比如飞书接口变慢、SMS 网关报错率升高，都能在这个指标上第一时间看到）。
        long start = System.nanoTime();
        boolean pushSucceeded = false;
        try {
            msgService.pushMsg(base);
            pushSucceeded = true;
        } finally {
            meterRegistry.timer("msgcenter.msg.push.duration",
                            "channel", String.valueOf(tp.getChannel()),
                            "result", pushSucceeded ? "success" : "fail")
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }

        // 6. 存储消息发送记录
        try{
            msgRecordService.CreateOrUpdateMsgRecord(sendMsgReq.getMsgID(),sendMsgReq,tp, MsgStatus.Succeed);
        }catch (Exception e){
            log.error("存储消息发送记录失败， msgId={}", sendMsgReq.getMsgID(), e);
        }

    }

}
