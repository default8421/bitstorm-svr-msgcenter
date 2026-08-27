package cn.bitoffer.msgcenter.biz.common;

/**
 * 接入中台的四条业务线。每条线有独立来源标识、渠道、优先级、模板和限流配额。
 *
 * @author LQH
 */
public enum BizSource {

    ACCOUNT("biz-account", "账户安全", 2, 3, "账户安全通知",
            "【安全提醒】${event}\n${detail}", 300),

    TRADE("biz-trade", "交易订单", 2, 2, "交易订单通知",
            "【订单通知】${event}\n订单号：${orderNo}\n${detail}", 300),

    MARKETING("biz-marketing", "营销触达", 1, 1, "营销活动通知",
            "【${event}】${detail}", 200),

    SYSTEM("biz-system", "系统告警", 3, 3, "系统告警通知",
            "【系统告警】${event}\n${detail}", 60);

    private final String sourceId;
    private final String displayName;
    private final int channel;
    private final int priority;
    private final String templateName;
    private final String templateContent;
    private final int quotaPerSecond;

    BizSource(String sourceId, String displayName, int channel, int priority,
            String templateName, String templateContent, int quotaPerSecond) {
        this.sourceId = sourceId;
        this.displayName = displayName;
        this.channel = channel;
        this.priority = priority;
        this.templateName = templateName;
        this.templateContent = templateContent;
        this.quotaPerSecond = quotaPerSecond;
    }

    public String sourceId() {
        return sourceId;
    }

    public String displayName() {
        return displayName;
    }

    public int channel() {
        return channel;
    }

    public int priority() {
        return priority;
    }

    public String templateName() {
        return templateName;
    }

    public String templateContent() {
        return templateContent;
    }

    public int quotaPerSecond() {
        return quotaPerSecond;
    }

    public String channelName() {
        return channelName(channel);
    }

    public static String channelName(int channel) {
        switch (channel) {
            case 1:
                return "邮件";
            case 2:
                return "短信";
            case 3:
                return "飞书";
            default:
                return "未知";
        }
    }

    public static BizSource fromSourceId(String sourceId) {
        if (sourceId == null) {
            return null;
        }
        for (BizSource s : values()) {
            if (s.sourceId.equals(sourceId)) {
                return s;
            }
        }
        return null;
    }

    public static String displayNameOf(String sourceId) {
        BizSource found = fromSourceId(sourceId);
        return found == null ? (sourceId == null ? "未知" : sourceId) : found.displayName;
    }
}
