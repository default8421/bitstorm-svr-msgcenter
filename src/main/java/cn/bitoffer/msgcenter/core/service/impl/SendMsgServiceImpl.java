package cn.bitoffer.msgcenter.core.service.impl;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.enums.TemplateStatus;
import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.flowcontrol.AdmissionGuard;
import cn.bitoffer.msgcenter.core.manager.SendMsgManager;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.TemplateService;
import cn.bitoffer.msgcenter.core.service.SendMsgService;
import cn.bitoffer.msgcenter.core.tenant.TenantContext;
import cn.bitoffer.msgcenter.core.utils.TemplateRenderer;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SendMsgServiceImpl。
 *
 * @author LQH
 */
@Service
@Slf4j
public class SendMsgServiceImpl implements SendMsgService {

    @Autowired
    private TemplateService templateService;

    @Autowired
    SendMsgConf sendMsgConf;

    @Autowired
    SendMsgManager sendMsgManager;

    @Autowired
    AdmissionGuard admissionGuard;

    @Autowired
    MeterRegistry meterRegistry;


    @Override
    public String SendMsg(SendMsgReq sendMsgReq) {
        TemplateModel tp = templateService.GetTemplateWithCache(sendMsgReq.getTemplateId());
        if (tp == null) {
            log.warn("模板不存在 templateId:{}", sendMsgReq.getTemplateId());
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "模板不存在 templateId:" + sendMsgReq.getTemplateId());
        }

        String tenant = TenantContext.current();
        if (tenant != null) {
            if (tp.getTenantId() != null && !tenant.equals(tp.getTenantId())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "模板不属于当前用户");
            }
            sendMsgReq.setTenantId(tenant);
        } else if (StringUtils.isBlank(sendMsgReq.getTenantId())) {
            sendMsgReq.setTenantId(tp.getTenantId());
        }
        if (StringUtils.isBlank(sendMsgReq.getSubject()) && StringUtils.isNotBlank(tp.getSubject())) {
            sendMsgReq.setSubject(tp.getSubject());
        }
        if (StringUtils.isBlank(sendMsgReq.getSubject())) {
            sendMsgReq.setSubject("通知");
        }

        List<String> recipients = TemplateRenderer.splitRecipients(sendMsgReq.getTo());
        if (recipients.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "收件人不能为空");
        }
        if (recipients.size() > TemplateRenderer.maxRecipients()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "单次最多 " + TemplateRenderer.maxRecipients() + " 个收件人");
        }
        if (recipients.size() == 1) {
            sendMsgReq.setTo(recipients.get(0));
            return sendOne(sendMsgReq, tp);
        }
        String lastId = null;
        for (String oneTo : recipients) {
            lastId = sendOne(copyForRecipient(sendMsgReq, oneTo), tp);
        }
        return lastId;
    }

    private String sendOne(SendMsgReq sendMsgReq, TemplateModel tp) {
        meterRegistry.counter("msgcenter.msg.received", "channel", String.valueOf(tp.getChannel())).increment();
        if (tp.getStatus() == null || tp.getStatus() != TemplateStatus.TEMPLATE_STATUS_NORMAL.getStatus()) {
            throw new BusinessException(ErrorCode.TEMPLATE_STATUS_ERROR, "模板尚未准备好，检查模板状态");
        }

        boolean isTimerMsg = sendMsgReq.getSendTimestamp() != null;

        // 入口只判断中台自身还能不能可靠受理，不再做来源/渠道的业务限流：渠道发送速度由消费侧
        // 的分层令牌桶控制，消息在队列里等待即可，不需要在入口给它写一个与真实容量无关的延迟。
        admissionGuard.check(sendMsgReq.getPriority());

        if (isTimerMsg) {
            return sendMsgManager.SendToTimer(sendMsgReq);
        }

        String msgId = sendMsgConf.isMysqlAsMq()
                ? sendMsgManager.SendToMysql(sendMsgReq)
                : sendMsgManager.SendToMq(sendMsgReq);

        meterRegistry.counter("msgcenter.msg.enqueue", "channel", String.valueOf(tp.getChannel()),
                "result", StringUtils.isEmpty(msgId) ? "fail" : "success").increment();

        // 入口到此为止：校验 + 准入 + 入队即返回。消息落进队列/Broker 那一行就是持久化保证，入口
        // 不再写消息记录——记录是消费侧维护的状态投影，谁处理谁写，避免把一次 MySQL 写压在响应路径上。
        return msgId;
    }

    private static SendMsgReq copyForRecipient(SendMsgReq src, String to) {
        SendMsgReq one = new SendMsgReq();
        one.setTo(to);
        one.setSubject(src.getSubject());
        one.setPriority(src.getPriority());
        one.setTemplateId(src.getTemplateId());
        one.setSendTimestamp(src.getSendTimestamp());
        one.setTenantId(src.getTenantId());
        Map<String, String> data = src.getTemplateData();
        if (data != null) {
            one.setTemplateData(new HashMap<>(data));
        }
        return one;
    }
}
