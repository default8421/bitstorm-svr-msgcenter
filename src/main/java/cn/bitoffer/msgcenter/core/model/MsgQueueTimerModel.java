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
public class MsgQueueTimerModel extends BaseModel implements Serializable {

    private Long id;

    private String msgId;

    private String req;

    private Long sendTimestamp;

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

    public String getReq() {
        return req;
    }

    public void setReq(String req) {
        this.req = req;
    }

    public Long getSendTimestamp() {
        return sendTimestamp;
    }

    public void setSendTimestamp(Long sendTimestamp) {
        this.sendTimestamp = sendTimestamp;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "MsgQueueTimerModel{" +
                "id=" + id +
                ", msgId='" + msgId + '\'' +
                ", req='" + req + '\'' +
                ", sendTimestamp=" + sendTimestamp +
                ", status=" + status +
                '}';
    }
}
