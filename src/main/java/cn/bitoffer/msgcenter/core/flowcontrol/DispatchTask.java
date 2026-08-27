package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;

/**
 * 已经从持久化队列领取、正在等待发送许可的一条消息。
 *
 * @param enqueuedAtMillis 进入本地等待区的时间，用于观测调度等待时长
 * @author LQH
 */
public record DispatchTask(SendMsgReq req, DispatchContext ctx, long enqueuedAtMillis) {
}
