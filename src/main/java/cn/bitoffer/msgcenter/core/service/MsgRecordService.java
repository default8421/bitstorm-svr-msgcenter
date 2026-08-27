package cn.bitoffer.msgcenter.core.service;

import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContext;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;

/**
 * 消息记录 = 消费侧维护的状态投影。
 *
 * <p>入口({@code /msg/send_msg})只负责入队，不写这张表；持久化保证来自队列/Broker 那一行。记录的
 * 生命周期由消费侧驱动：处理到哪一步就把状态写到哪一步（Processing → Succeed/Failed/Suppressed），
 * 全程用 upsert，谁先落到某个 msgId 谁就写全字段，后来者只翻状态。幂等判断以强一致的记录终态为准。
 *
 * @author LQH
 */
public interface MsgRecordService {

    MsgRecordModel GetMsgRecordWithCache(String msgId);

    /**
     * Idempotency gate for the consumer: checks the DB directly (bypassing the read cache, since a
     * stale cached miss must never cause a duplicate real push) whether this msgId has already been
     * pushed successfully. Callers should skip the actual push when this returns true — this is what
     * protects against a broker redelivering an at-least-once message (e.g. Kafka re-delivering after
     * a crash between push and offset-commit) turning into a second real send to the user.
     */
    boolean isAlreadySucceeded(String msgId);

    /**
     * 消费侧在拿到模板、掌握完整字段时写入投影（推送前置 Processing、推送成功置 Succeed）。
     */
    void CreateOrUpdateMsgRecord(String msgId,SendMsgReq sendMsgReq, TemplateModel tp, MsgStatus status);

    /**
     * 记录一个发生在 {@code DealOneMsg} 之外的终态（防打扰 Suppressed、永久失败 Failed）。此时通常
     * 没有模板，用消费侧已解析出的 {@link DispatchContext} 补齐来源/渠道/租户；{@code ctx} 为 null
     * （连投递上下文都解析不出来，例如模板已删除）时用占位来源，保证记录仍然产生、便于排查与统计。
     */
    void recordTerminalState(SendMsgReq sendMsgReq, DispatchContext ctx, MsgStatus status);
}
