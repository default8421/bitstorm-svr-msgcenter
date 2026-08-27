package cn.bitoffer.msgcenter.core.manager;

import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;

/**
 * SendMsgManager{。
 *
 * @author LQH
 */
public interface SendMsgManager{
    public String SendToMysql(SendMsgReq sendMsgReq);

    public String SendToMq(SendMsgReq sendMsgReq);

    public String SendToTimer(SendMsgReq sendMsgReq);
}
