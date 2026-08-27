package cn.bitoffer.msgcenter.biz.common;

import java.util.Map;

/**
 * 一条业务事件，组装成 SendMsgReq 后投入内核。
 *
 * @param source 所属业务线
 * @param event  事件名
 * @param to     收件人
 * @param data   模板变量
 * @author LQH
 */
public record BizEvent(BizSource source, String event, String to, Map<String, String> data) {
}
