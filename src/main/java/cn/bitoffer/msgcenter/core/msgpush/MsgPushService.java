package cn.bitoffer.msgcenter.core.msgpush;

import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.msgpush.base.ChannelMsgBase;

/**
 * MsgPushService。
 *
 * @author LQH
 */
public interface MsgPushService {
    void pushMsg(ChannelMsgBase msgBase);
}
