package cn.bitoffer.msgcenter.core.msgpush.channel;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.msgpush.MsgPushService;
import cn.bitoffer.msgcenter.core.msgpush.base.ChannelMsgBase;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import com.tencentcloudapi.sms.v20210111.SmsClient;
import com.tencentcloudapi.sms.v20210111.models.SendSmsRequest;
import com.tencentcloudapi.sms.v20210111.models.SendSmsResponse;
import com.tencentcloudapi.sms.v20210111.models.SendStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 短信渠道：未开启时记日志并视为已发送，开启后走腾讯云。
 *
 * @author LQH
 */
@Service
@Slf4j
public class SMSServiceImpl implements MsgPushService {

    @Autowired
    SendMsgConf sendMsgConf;

    @Override
    public void pushMsg(ChannelMsgBase msgBase) {
        if (!sendMsgConf.isSmsTencentEnabled()) {
            log.info("发送 SMS 短信!!!!! content:" + msgBase.getContent());
            return;
        }
        sendViaTencentCloud(msgBase);
    }

    private void sendViaTencentCloud(ChannelMsgBase msgBase) {
        try {
            Credential credential = new Credential(sendMsgConf.getSmsTencentSecretId(), sendMsgConf.getSmsTencentSecretKey());

            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint("sms.tencentcloudapi.com");
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);

            SmsClient client = new SmsClient(credential, sendMsgConf.getSmsTencentRegion(), clientProfile);

            SendSmsRequest req = new SendSmsRequest();
            req.setSmsSdkAppId(sendMsgConf.getSmsTencentSdkAppId());
            req.setSignName(sendMsgConf.getSmsTencentSignName());
            req.setTemplateId(sendMsgConf.getSmsTencentTemplateId());
            req.setPhoneNumberSet(new String[]{toE164(msgBase.getTo())});
            req.setTemplateParamSet(templateParams(msgBase.getTemplateData()));

            SendSmsResponse resp = client.SendSms(req);
            logResult(resp);
        } catch (TencentCloudSDKException e) {
            log.error("腾讯云短信发送失败, to={}", msgBase.getTo(), e);
            throw new BusinessException(ErrorCode.PUSH_MSG_ERROR, "sms push msg error: " + e.getMessage());
        }
    }

    /**
     * Tencent's registered template placeholders ({1}, {2}, ...) are positional, so the
     * caller-supplied {@code templateData} map is flattened in insertion order (Jackson preserves
     * JSON field order via LinkedHashMap) to build the ordered param array Tencent expects.
     */
    private String[] templateParams(Map<String, String> templateData) {
        if (templateData == null || templateData.isEmpty()) {
            return new String[0];
        }
        return templateData.values().toArray(new String[0]);
    }

    private String toE164(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.startsWith("+")) {
            return trimmed;
        }
        return "+86" + trimmed;
    }

    /**
     * 腾讯云的 SendSms 是“HTTP 调用成功”不代表“短信下发成功”：真正的结果在每个号码的 SendStatus.code
     * 里，只有 "Ok" 才算成功。这里逐条检查，命中任何非 Ok 就抛异常——否则失败会被上层记成 Succeed，
     * 既不会进重试、也污染成功率指标。
     */
    private void logResult(SendSmsResponse resp) {
        SendStatus[] statuses = resp.getSendStatusSet();
        if (statuses == null || statuses.length == 0) {
            throw new BusinessException(ErrorCode.PUSH_MSG_ERROR,
                    "sms send returned no status, requestId=" + resp.getRequestId());
        }
        for (SendStatus status : statuses) {
            if ("Ok".equals(status.getCode())) {
                log.info("腾讯云短信发送成功: phone={} serialNo={}", status.getPhoneNumber(), status.getSerialNo());
            } else {
                log.warn("腾讯云短信发送失败: phone={} code={} message={}",
                        status.getPhoneNumber(), status.getCode(), status.getMessage());
                throw new BusinessException(ErrorCode.PUSH_MSG_ERROR,
                        "sms send failed: code=" + status.getCode() + ", message=" + status.getMessage());
            }
        }
    }
}
