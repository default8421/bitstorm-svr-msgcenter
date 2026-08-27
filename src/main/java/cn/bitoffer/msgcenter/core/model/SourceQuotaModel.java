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
public class SourceQuotaModel extends BaseModel implements Serializable {

    private Long id;

    private int num;

    private int unit;

    private int channel;

    private String sourceId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getNum() {
        return num;
    }

    public void setNum(int num) {
        this.num = num;
    }

    public int getUnit() {
        return unit;
    }

    public void setUnit(int unit) {
        this.unit = unit;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }
}
