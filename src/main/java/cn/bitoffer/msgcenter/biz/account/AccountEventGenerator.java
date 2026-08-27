package cn.bitoffer.msgcenter.biz.account;

import cn.bitoffer.msgcenter.biz.common.BizEvent;
import cn.bitoffer.msgcenter.biz.common.BizSource;
import cn.bitoffer.msgcenter.biz.common.FakeData;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 账户安全事件：验证码、异地登录、改密。
 *
 * @author LQH
 */
@Component
public class AccountEventGenerator {

    public BizEvent next() {
        Map<String, String> data = new LinkedHashMap<>();
        int pick = FakeData.r().nextInt(3);
        String event;
        if (pick == 0) {
            event = "登录验证码";
            data.put("detail", "您的验证码 " + FakeData.code() + "，5 分钟内有效，请勿泄露给他人");
        } else if (pick == 1) {
            event = "异地登录提醒";
            data.put("detail", "检测到您的账号于 " + FakeData.pickOne(FakeData.CITIES)
                    + " 登录，如非本人操作请立即修改密码");
        } else {
            event = "密码修改成功";
            data.put("detail", "您的登录密码已于刚刚成功修改，如非本人操作请尽快联系客服");
        }
        data.put("event", event);
        return new BizEvent(BizSource.ACCOUNT, event, FakeData.maskedPhone(), data);
    }
}
