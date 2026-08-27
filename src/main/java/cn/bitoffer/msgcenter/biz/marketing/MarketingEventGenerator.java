package cn.bitoffer.msgcenter.biz.marketing;

import cn.bitoffer.msgcenter.biz.common.BizEvent;
import cn.bitoffer.msgcenter.biz.common.BizSource;
import cn.bitoffer.msgcenter.biz.common.FakeData;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 营销触达事件：优惠券、大促、积分。
 *
 * @author LQH
 */
@Component
public class MarketingEventGenerator {

    private static final String[] TITLES =
            {"限时优惠券", "品牌大促预告", "积分到期提醒", "会员专享福利", "新品尝鲜"};

    public BizEvent next() {
        Map<String, String> data = new LinkedHashMap<>();
        String event = FakeData.pickOne(TITLES);
        String detail;
        switch (event) {
            case "限时优惠券":
                detail = "您有一张 ¥" + (FakeData.r().nextInt(9) + 1) * 10 + " 优惠券待领取，今日 24 点前有效";
                break;
            case "积分到期提醒":
                detail = "您有 " + (FakeData.r().nextInt(9) + 1) * 100 + " 积分将于本月底到期，快来兑换好礼";
                break;
            case "新品尝鲜":
                detail = "为你精选的新品已上线，专属折扣仅此一天";
                break;
            default:
                detail = "品牌大促今晚 20:00 开抢，热销爆款先到先得";
        }
        data.put("event", event);
        data.put("detail", detail);
        return new BizEvent(BizSource.MARKETING, event, FakeData.fakeEmail(), data);
    }
}
