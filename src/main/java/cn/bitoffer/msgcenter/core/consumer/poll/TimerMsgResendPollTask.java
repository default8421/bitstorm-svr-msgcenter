package cn.bitoffer.msgcenter.core.consumer.poll;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.enums.TemplateStatus;
import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.manager.SendMsgManager;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueTimerMapper;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.TemplateService;
import cn.bitoffer.msgcenter.core.service.MsgRecordService;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * TimerMsgResendPollTask。
 *
 * @author LQH
 */
@Slf4j
@Component
public class TimerMsgResendPollTask {

    @Autowired
    MsgQueueTimerMapper msgQueueTimerMapper;


    @Autowired
    SendMsgManager sendMsgManager;

    @Autowired
    SendMsgConf sendMsgConf;

    @Autowired
    MsgRecordService msgRecordService;

    @Autowired
    TemplateService templateService;

    @Async("timerMsgPoll")
    public void asyncHandleMsg(String  reqStr) {
        SendMsgReq sendMsgReq = JSONUtil.parseObject(reqStr,SendMsgReq.class);
        if (sendMsgReq == null){
            return;
        }
        TemplateModel tp = templateService.GetTemplateWithCache(sendMsgReq.getTemplateId());
        if(tp == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "模板不存在 templateId:" + sendMsgReq.getTemplateId());
        }
        // status 是装箱 Integer（原因见 TemplateModel），显式判 null 再比较，避免自动拆箱 NPE。
        if(tp.getStatus() == null || tp.getStatus() != TemplateStatus.TEMPLATE_STATUS_NORMAL.getStatus()){
            throw new BusinessException(ErrorCode.TEMPLATE_STATUS_ERROR, "模板尚未准备好，检查模板状态");
        }
        // 到点后把定时消息转投实时队列。这里不再“catch 后立刻硬重试一次并无条件置成功”——那种写法
        // 一旦二次入队也失败，异常会直接冒出方法，反而把定时行留在 Processing，而之前的代码却已经把
        // success 当成了 true。现在改为：入队失败就保留 Processing 状态，交给 recoverStaleProcessing
        // 回收后重新领取重试，绝不把失败当成功收尾。
        try {
            if(sendMsgConf.isMysqlAsMq()){
                sendMsgManager.SendToMysql(sendMsgReq);
            }else{
                sendMsgManager.SendToMq(sendMsgReq);
            }
        }catch (Exception e){
            log.warn("定时消息转投队列失败，保留 Processing 交由回收机制重试 msgId={}",
                    sendMsgReq.getMsgID(), e);
            return;
        }

        // 入队成功：记录置回 Pending（真正的 Succeed 由消费侧推送成功后写），并把定时队列行标记为
        // 已交接，避免重复触发。
        msgRecordService.CreateOrUpdateMsgRecord(sendMsgReq.getMsgID(),sendMsgReq,tp,MsgStatus.Pending);
        msgQueueTimerMapper.setStatus(sendMsgReq.getMsgID(), MsgStatus.Succeed.getStatus());
    }
}
