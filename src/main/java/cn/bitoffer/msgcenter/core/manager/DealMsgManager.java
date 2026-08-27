package cn.bitoffer.msgcenter.core.manager;

import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;

/**
 * DealMsgManager。
 *
 * @author LQH
 */
public interface DealMsgManager {

    public void DealOneMsg(SendMsgReq sendMsgReq);
}
