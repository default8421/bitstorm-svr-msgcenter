package cn.bitoffer.msgcenter.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 发送相关配置：队列后端、重试、邮件/短信/飞书开关。
 *
 * @author LQH
 */
@Component
public class SendMsgConf {

    @Value("${send-msg-conf.mysql-as-mq}")
    private boolean mysqlAsMq;

    @Value("${send-msg-conf.open-cache}")
    private boolean openCache;

    @Value("${send-msg-conf.max-retry-count}")
    private int maxRetryCount;

    // 邮件开关，默认关闭走日志桩
    @Value("${send-msg-conf.email-enabled:false}")
    private boolean emailEnabled;

    @Value("${send-msg-conf.email-account}")
    private String emailAccount;

    @Value("${send-msg-conf.email-auth-code}")
    private String emailAuthCode;

    @Value("${send-msg-conf.email-host}")
    private String emailHost;

    @Value("${send-msg-conf.email-port}")
    private String emailPort;

    // 短信开关默认写在注解里，方便单测只覆盖部分配置
    @Value("${send-msg-conf.sms-tencent-enabled:false}")
    private boolean smsTencentEnabled;

    @Value("${send-msg-conf.sms-tencent-region:ap-guangzhou}")
    private String smsTencentRegion;

    @Value("${send-msg-conf.sms-tencent-secret-id:}")
    private String smsTencentSecretId;

    @Value("${send-msg-conf.sms-tencent-secret-key:}")
    private String smsTencentSecretKey;

    @Value("${send-msg-conf.sms-tencent-sdk-app-id:}")
    private String smsTencentSdkAppId;

    @Value("${send-msg-conf.sms-tencent-sign-name:}")
    private String smsTencentSignName;

    @Value("${send-msg-conf.sms-tencent-template-id:}")
    private String smsTencentTemplateId;

    // Lark/Feishu custom-bot webhook: no enterprise verification or template review needed,
    // just a webhook URL from a group chat's bot settings. Disabled by default -> log-only stub.
    @Value("${send-msg-conf.lark-webhook-enabled:false}")
    private boolean larkWebhookEnabled;

    @Value("${send-msg-conf.lark-webhook-url:}")
    private String larkWebhookUrl;

    // Optional: only needed if the bot has "signature verification" turned on.
    @Value("${send-msg-conf.lark-webhook-secret:}")
    private String larkWebhookSecret;

    public boolean isMysqlAsMq() {
        return mysqlAsMq;
    }

    public void setMysqlAsMq(boolean mysqlAsMq) {
        this.mysqlAsMq = mysqlAsMq;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public String getEmailAccount() {
        return emailAccount;
    }

    public void setEmailAccount(String emailAccount) {
        this.emailAccount = emailAccount;
    }

    public String getEmailAuthCode() {
        return emailAuthCode;
    }

    public void setEmailAuthCode(String emailAuthCode) {
        this.emailAuthCode = emailAuthCode;
    }

    public String getEmailHost() {
        return emailHost;
    }

    public void setEmailHost(String emailHost) {
        this.emailHost = emailHost;
    }

    public String getEmailPort() {
        return emailPort;
    }

    public void setEmailPort(String emailPort) {
        this.emailPort = emailPort;
    }

    public boolean isOpenCache() {
        return openCache;
    }

    public void setOpenCache(boolean openCache) {
        this.openCache = openCache;
    }

    public int getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(int maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }

    public boolean isSmsTencentEnabled() {
        return smsTencentEnabled;
    }

    public void setSmsTencentEnabled(boolean smsTencentEnabled) {
        this.smsTencentEnabled = smsTencentEnabled;
    }

    public String getSmsTencentRegion() {
        return smsTencentRegion;
    }

    public void setSmsTencentRegion(String smsTencentRegion) {
        this.smsTencentRegion = smsTencentRegion;
    }

    public String getSmsTencentSecretId() {
        return smsTencentSecretId;
    }

    public void setSmsTencentSecretId(String smsTencentSecretId) {
        this.smsTencentSecretId = smsTencentSecretId;
    }

    public String getSmsTencentSecretKey() {
        return smsTencentSecretKey;
    }

    public void setSmsTencentSecretKey(String smsTencentSecretKey) {
        this.smsTencentSecretKey = smsTencentSecretKey;
    }

    public String getSmsTencentSdkAppId() {
        return smsTencentSdkAppId;
    }

    public void setSmsTencentSdkAppId(String smsTencentSdkAppId) {
        this.smsTencentSdkAppId = smsTencentSdkAppId;
    }

    public String getSmsTencentSignName() {
        return smsTencentSignName;
    }

    public void setSmsTencentSignName(String smsTencentSignName) {
        this.smsTencentSignName = smsTencentSignName;
    }

    public String getSmsTencentTemplateId() {
        return smsTencentTemplateId;
    }

    public void setSmsTencentTemplateId(String smsTencentTemplateId) {
        this.smsTencentTemplateId = smsTencentTemplateId;
    }

    public boolean isLarkWebhookEnabled() {
        return larkWebhookEnabled;
    }

    public void setLarkWebhookEnabled(boolean larkWebhookEnabled) {
        this.larkWebhookEnabled = larkWebhookEnabled;
    }

    public String getLarkWebhookUrl() {
        return larkWebhookUrl;
    }

    public void setLarkWebhookUrl(String larkWebhookUrl) {
        this.larkWebhookUrl = larkWebhookUrl;
    }

    public String getLarkWebhookSecret() {
        return larkWebhookSecret;
    }

    public void setLarkWebhookSecret(String larkWebhookSecret) {
        this.larkWebhookSecret = larkWebhookSecret;
    }
}