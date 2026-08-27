package cn.bitoffer.msgcenter.core.consumer.poll;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContext;
import cn.bitoffer.msgcenter.core.manager.DealMsgManager;
import cn.bitoffer.msgcenter.core.manager.SendMsgManager;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import cn.bitoffer.msgcenter.core.model.MsgRecordModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.retry.BackoffPolicy;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * MysqlMsgPollTask。
 *
 * @author LQH
 */
@Slf4j
@Component
public class MysqlMsgPollTask {
    @Autowired
    DealMsgManager dealMsgManager;

    @Autowired
    MsgQueueMapper msgQueueMapper;

    @Autowired
    MsgRecordMapper msgRecordMapper;

    @Autowired
    SendMsgConf sendMsgConf;

    @Autowired
    SendMsgManager sendMsgManager;

    @Autowired
    MsgRecordService msgRecordService;

    @Autowired
    @Qualifier("legacyRetryBackoff")
    BackoffPolicy legacyRetryBackoff;

    /**
     * 把消息按防打扰终态收尾：队列行和消息记录都置为 SUPPRESSED。
     *
     * <p>不写 Failed、不进重试队列——业务规则决定不发，和"发失败了"是两回事。这条消息不会进入
     * DealOneMsg，用调度时已解析出的 ctx 补齐来源/渠道把投影记录建出来。
     */
    public void markSuppressed(SendMsgReq req, DispatchContext ctx) {
        setQueueStatus(req, MsgStatus.Suppressed);
        msgRecordService.recordTerminalState(req, ctx, MsgStatus.Suppressed);
    }

    /**
     * 永久性错误（例如模板已删除、投递上下文都解析不出来）直接落终态，不进重试队列。此时没有 ctx，
     * 用占位来源把记录建出来，保证这条消息在查询面板可见、便于排查。
     */
    public void markPermanentlyFailed(SendMsgReq req) {
        setQueueStatus(req, MsgStatus.Failed);
        msgRecordService.recordTerminalState(req, null, MsgStatus.Failed);
    }

    /** 把已领取但没能进入调度器的消息还回 PENDING，等下一轮重新领取。 */
    public void releaseToPending(SendMsgReq req) {
        setQueueStatus(req, MsgStatus.Pending);
    }

    private void setQueueStatus(SendMsgReq req, MsgStatus status) {
        String tableName = Constants.TableNamePre_MsgQueue
                + PriorityEnum.GetPriorityStr(req.getPriority());
        msgQueueMapper.setStatus(tableName, req.getMsgID(), status.getStatus());
    }

    @Async("mysqlMsgDealPoll")
    public void asyncHandleMsg(SendMsgReq req) {

        String tableName = Constants.TableNamePre_MsgQueue+ PriorityEnum.GetPriorityStr(req.getPriority());

        // 走消息发送逻辑
        try{
            dealMsgManager.DealOneMsg(req);
            // 发送成功
            msgQueueMapper.setStatus(tableName,req.getMsgID(),MsgStatus.Succeed.getStatus());
        }catch (Exception e){
            if(req.getPriority() != PriorityEnum.PRIORITY_RETRY.getPriorty()){
                msgQueueMapper.setStatus(tableName,req.getMsgID(),MsgStatus.Failed.getStatus());
            }
            // 走重试队列
            dealRetryMysqlQueue(req);
        }
    }

    private void dealRetryMysqlQueue(SendMsgReq req){
        // 增加重试次数并检查是否达到上限；消息记录可能尚未落库，容忍为空避免 NPE
        MsgRecordModel mrd = msgRecordMapper.getMsgById(req.getMsgID());
        int currentRetry = mrd == null ? 0 : mrd.getRetryCount();
        String retryTableName = Constants.TableNamePre_MsgQueue+ PriorityEnum.GetPriorityStr(PriorityEnum.PRIORITY_RETRY.getPriorty());

        // 检查重试次数是否到达上限，达到则进入终态(DEAD)
        if(currentRetry >= sendMsgConf.getMaxRetryCount()){
            log.info("消息{}已达到最大重试次数，不再重试:{}", req.getMsgID(),
                    sendMsgConf.getMaxRetryCount());
            // 更新【消息记录】状态为最终失败
            msgRecordMapper.setStatus(req.getMsgID(), MsgStatus.Failed.getStatus());
            // 更新重试队列状态为最终失败
            msgQueueMapper.setStatus( retryTableName, req.getMsgID(), MsgStatus.Failed.getStatus());
            return;
        }
        // 重试次数+1
        int newCount = currentRetry + 1;
        msgRecordMapper.incrementRetryCount(req.getMsgID(),newCount);

        // 指数退避+抖动计算下一次重试延迟，避免立即重投造成的重试风暴（调度线程不 sleep）
        long delayMillis = legacyRetryBackoff.delayMillis(newCount);
        int delaySeconds = (int) Math.max(1L, delayMillis / 1000L);

        // 判断重试队列表中是否已经存在
        MsgQueueModel msgQueueModel = msgQueueMapper.getMsgById(retryTableName,req.getMsgID());
        if(msgQueueModel == null){
            // 重新发送消息到重试队列
            req.setPriority(PriorityEnum.PRIORITY_RETRY.getPriorty());
            sendMsgManager.SendToMysql(req);
        }
        // 设置有界延迟的 next_attempt_at，claim 时只会领取到期的重试消息
        msgQueueMapper.setRetryScheduled(retryTableName, req.getMsgID(),
                MsgStatus.Pending.getStatus(), delaySeconds);

        log.info("消息{}已加入MySQL重试队列，当前重试次数:{}，延迟{}秒", req.getMsgID(), newCount,
                delaySeconds);
    }
}
