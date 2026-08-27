package cn.bitoffer.msgcenter.biz.system;

import cn.bitoffer.msgcenter.biz.common.BizEvent;
import cn.bitoffer.msgcenter.biz.common.BizSource;
import cn.bitoffer.msgcenter.biz.common.FakeData;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 系统告警事件：错误率、延迟、资源超阈值。走飞书渠道。
 *
 * @author LQH
 */
@Component
public class SystemEventGenerator {

    public BizEvent next() {
        Map<String, String> data = new LinkedHashMap<>();
        String service = FakeData.pickOne(FakeData.SERVICES);
        int pick = FakeData.r().nextInt(3);
        String event;
        if (pick == 0) {
            event = "接口错误率告警";
            data.put("detail", service + " 5xx 错误率达到 " + (FakeData.r().nextInt(15) + 5) + "%，请立即排查");
        } else if (pick == 1) {
            event = "响应延迟告警";
            data.put("detail", service + " P99 延迟 " + (FakeData.r().nextInt(2000) + 800) + "ms，已超过阈值");
        } else {
            event = "资源使用告警";
            data.put("detail", "node-" + (FakeData.r().nextInt(20) + 1) + " CPU 使用率 "
                    + (FakeData.r().nextInt(20) + 80) + "%");
        }
        data.put("event", event);
        return new BizEvent(BizSource.SYSTEM, event, "ops", data);
    }
}
