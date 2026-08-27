package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.enums.PriorityEnum;

/**
 * 一条消息在消费侧被调度时所需的治理维度。
 *
 * @author LQH
 */
public record DispatchContext(String tenantId, String sourceId, int channel, int priority,
        String recipient) {

    private static final String UNKNOWN = "unknown";

    public DispatchContext {
        tenantId = normalize(tenantId);
        sourceId = normalize(sourceId);
        recipient = normalize(recipient);
    }

    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? UNKNOWN : value;
    }

    /** 高优先级可以借用普通业务的空闲份额，但仍受平台总量与渠道硬限额约束。 */
    public boolean isHighPriority() {
        return priority == PriorityEnum.PRIORITY_HIGH.getPriorty();
    }

    public String tenantQuotaKey() {
        return tenantId + ":" + sourceId + ":" + channel;
    }
}
