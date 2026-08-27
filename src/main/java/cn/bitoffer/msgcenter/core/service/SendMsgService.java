package cn.bitoffer.msgcenter.core.service;

import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;

/**
 * SendMsgService。
 *
 * @author LQH
 */
public interface SendMsgService {

    String SendMsg(SendMsgReq sendMsgReq);

}
