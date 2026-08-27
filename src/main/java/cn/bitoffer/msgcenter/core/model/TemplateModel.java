package cn.bitoffer.msgcenter.core.model;


import cn.bitoffer.common.model.BaseModel;

import java.io.Serializable;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * TemplateModel 消息模板
 *
 *
 *
 * @author LQH
 */
public class TemplateModel extends BaseModel implements Serializable {

    /** Validation group for {@code POST /msg/create_template}: server generates templateId, so it's
     * not required here, but the content fields that make the template usable are. */
    public interface OnCreate {}

    /** Validation group for {@code POST /msg/update_template}: this is a partial PATCH-style update
     * (see TemplateMapper.xml's {@code <if test="... != null">}), so only templateId (which row to
     * touch) is mandatory; every other field is optional and only applied when present. */
    public interface OnUpdate {}

    private Long id;

    @NotBlank(groups = OnUpdate.class, message = "templateId 不能为空")
    private String templateId;

    private String tenantId;

    private String relTemplateId;

    @NotBlank(groups = OnCreate.class, message = "name 不能为空")
    private String name;

    private String signName;

    @NotBlank(groups = OnCreate.class, message = "sourceId 不能为空")
    private String sourceId;

    // 用装箱 Integer 而不是原生 int：TemplateMapper.xml 的 update 语句靠
    // <if test="templateModel.channel != null"> 判断"这次请求到底有没有带这个字段、要不要更新它"。
    // 如果是原生 int，反序列化时缺省就是 0，装箱后恒不为 null，`!= null` 永远成立——PATCH 式的
    // update_template 只想改 status，却会把没传的 channel 一起当成"传了 0"写进数据库，
    // 把已经生效的模板悄悄改成一个不存在的渠道。装箱后，请求没带 channel 时它就是真正的 null，
    // MyBatis 的 <if> 才能按预期跳过这个字段、保持原值不变。
    @NotNull(groups = OnCreate.class, message = "channel 不能为空")
    @Min(value = 1, groups = OnCreate.class, message = "channel 必须是有效的推送渠道(1:邮件 2:短信 3:飞书)")
    private Integer channel;

    @NotBlank(groups = OnCreate.class, message = "subject 不能为空")
    private String subject;

    @NotBlank(groups = OnCreate.class, message = "content 不能为空")
    private String content;

    // 同 channel：装箱是为了让 update_template 的部分更新真正可用（见上）。
    private Integer status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getRelTemplateId() {
        return relTemplateId;
    }

    public void setRelTemplateId(String relTemplateId) {
        this.relTemplateId = relTemplateId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSignName() {
        return signName;
    }

    public void setSignName(String signName) {
        this.signName = signName;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public Integer getChannel() {
        return channel;
    }

    public void setChannel(Integer channel) {
        this.channel = channel;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "TemplateModel{" +
                "id=" + id +
                ", templateId='" + templateId + '\'' +
                ", relTemplateId='" + relTemplateId + '\'' +
                ", name='" + name + '\'' +
                ", signName='" + signName + '\'' +
                ", sourceId='" + sourceId + '\'' +
                ", channel=" + channel +
                ", subject='" + subject + '\'' +
                ", content='" + content + '\'' +
                ", status=" + status +
                '}';
    }
}
