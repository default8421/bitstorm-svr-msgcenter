package cn.bitoffer.msgcenter.core.enums;

/**
 * TemplateStatus。
 *
 * @author LQH
 */
public enum TemplateStatus {
    TEMPLATE_STATUS_PENDING(1),
    TEMPLATE_STATUS_NORMAL(2);

    private TemplateStatus(int status) {
        this.status = status;
    }
    private int status;

    public int getStatus() {
        return this.status;
    }
}
