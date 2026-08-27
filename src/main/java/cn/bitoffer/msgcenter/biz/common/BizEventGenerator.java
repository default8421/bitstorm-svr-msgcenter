package cn.bitoffer.msgcenter.biz.common;

import cn.bitoffer.msgcenter.biz.account.AccountEventGenerator;
import cn.bitoffer.msgcenter.biz.marketing.MarketingEventGenerator;
import cn.bitoffer.msgcenter.biz.system.SystemEventGenerator;
import cn.bitoffer.msgcenter.biz.trade.TradeEventGenerator;
import org.springframework.stereotype.Component;

/**
 * 按业务线分发到对应事件生成器。
 *
 * @author LQH
 */
@Component
public class BizEventGenerator {

    private final AccountEventGenerator account;
    private final TradeEventGenerator trade;
    private final MarketingEventGenerator marketing;
    private final SystemEventGenerator system;

    public BizEventGenerator(AccountEventGenerator account, TradeEventGenerator trade,
            MarketingEventGenerator marketing, SystemEventGenerator system) {
        this.account = account;
        this.trade = trade;
        this.marketing = marketing;
        this.system = system;
    }

    public BizEvent next(BizSource source) {
        switch (source) {
            case ACCOUNT:
                return account.next();
            case TRADE:
                return trade.next();
            case MARKETING:
                return marketing.next();
            case SYSTEM:
                return system.next();
            default:
                throw new IllegalArgumentException("未知业务线: " + source);
        }
    }
}
