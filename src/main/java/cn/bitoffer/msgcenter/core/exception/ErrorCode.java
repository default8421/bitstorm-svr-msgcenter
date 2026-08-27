package cn.bitoffer.msgcenter.core.exception;

/**
 * 自定义错误码
 *
 * @author LQH
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAMS_ERROR(40000, "请求参数错误"),
    RateLimit_ERROR(40002, "限流异常"),
    TEMPLATE_STATUS_ERROR(40003, "模板状态异常"),
    PUSH_MSG_ERROR(40004, "推送消息异常"),
    SYSTEM_ERROR(50000, "系统内部异常"),
    MSG_ENQUEUE_ERROR(50001, "消息入队失败，请重试"),
    ;


    /**
     * 状态码
     */
    private final int code;

    /**
     * 信息
     */
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

}
