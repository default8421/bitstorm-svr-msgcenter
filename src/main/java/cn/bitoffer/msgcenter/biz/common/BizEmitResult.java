package cn.bitoffer.msgcenter.biz.common;

/**
 * 手动单条发送的回执。
 *
 * @param sourceId 业务线标识
 * @param name     业务线名称
 * @param channel  渠道名
 * @param to       收件人
 * @param content  模板渲染后的报文
 * @param msgId    内核消息号
 * @param ok       是否入队成功
 * @param error    失败原因
 * @author LQH
 */
public record BizEmitResult(String sourceId, String name, String channel, String to,
        String content, String msgId, boolean ok, String error) {
}
