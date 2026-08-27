package cn.bitoffer.msgcenter.core.model.dto;

import java.util.Map;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

/**
 * SendMsgReq。
 *
 * @author LQH
 */
public class SendMsgReq {

    @NotBlank(message = "to 不能为空")
    private String to;

    // 可空：未传时由发送服务用模板主题回填，避免发送页覆盖模板。
    private String subject;

    // 1=低 2=中 3=高；4(重试)是队列内部状态，不允许调用方直接指定。
    @Min(value = 1, message = "priority 取值范围是 1(低)/2(中)/3(高)")
    @Max(value = 3, message = "priority 取值范围是 1(低)/2(中)/3(高)")
    private int priority;

    @NotBlank(message = "templateId 不能为空")
    private String templateId;

    private Map<String,String> templateData;

    private Long sendTimestamp;

    private String msgID;

    private String tenantId;

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public Map<String, String> getTemplateData() {
        return templateData;
    }

    public void setTemplateData(Map<String, String> templateData) {
        this.templateData = templateData;
    }

    public Long getSendTimestamp() {
        return sendTimestamp;
    }

    public void setSendTimestamp(Long sendTimestamp) {
        this.sendTimestamp = sendTimestamp;
    }

    public String getMsgID() {
        return msgID;
    }

    public void setMsgID(String msgID) {
        this.msgID = msgID;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }
}
