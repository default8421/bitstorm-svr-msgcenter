package cn.bitoffer.msgcenter.biz.trade;

import cn.bitoffer.msgcenter.biz.common.BizEvent;
import cn.bitoffer.msgcenter.biz.common.BizSource;
import cn.bitoffer.msgcenter.biz.common.FakeData;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 交易订单事件：下单、支付、发货、退款。
 *
 * @author LQH
 */
@Component
public class TradeEventGenerator {

    public BizEvent next() {
        Map<String, String> data = new LinkedHashMap<>();
        String orderNo = FakeData.orderNo();
        int pick = FakeData.r().nextInt(4);
        String event;
        if (pick == 0) {
            event = "下单成功";
            data.put("detail", "订单已提交，请在 30 分钟内完成支付");
        } else if (pick == 1) {
            event = "支付成功";
            data.put("detail", "支付金额 ¥" + FakeData.amount() + "，我们将尽快为您安排发货");
        } else if (pick == 2) {
            event = "订单已发货";
            data.put("detail", "您的包裹已发出，预计 3 日内送达，请注意查收");
        } else {
            event = "退款成功";
            data.put("detail", "退款 ¥" + FakeData.amount() + " 已原路退回，到账时间以银行为准");
        }
        data.put("event", event);
        data.put("orderNo", orderNo);
        return new BizEvent(BizSource.TRADE, event, FakeData.maskedPhone(), data);
    }
}
