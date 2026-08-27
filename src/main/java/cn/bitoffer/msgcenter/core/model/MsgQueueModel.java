package cn.bitoffer.msgcenter.core.model;


import cn.bitoffer.common.model.BaseModel;

import java.io.Serializable;

/**
 * TemplateModel 消息模板
 *
 *
 *
 * @author LQH
 */
public class MsgQueueModel extends BaseModel implements Serializable {

    private Long id;

    private String msgId;

    private String to;

    private String subject;

    private int priority;

    private int channel;

    private String templateId;

    private String templateData;

    private int status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

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

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getTemplateData() {
        return templateData;
    }

    public void setTemplateData(String templateData) {
        this.templateData = templateData;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MsgQueueModel{" +
                "id=" + id +
                ", msgId='" + msgId + '\'' +
                ", to='" + to + '\'' +
                ", subject='" + subject + '\'' +
                ", priority=" + priority +
                ", channel=" + channel +
                ", templateId='" + templateId + '\'' +
                ", templateData='" + templateData + '\'' +
                ", status=" + status +
                '}';
    }
}
