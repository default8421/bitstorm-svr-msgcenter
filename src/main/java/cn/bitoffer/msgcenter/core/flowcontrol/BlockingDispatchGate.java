package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.redis.QuotaUnavailableException;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 在当前线程上等待发送许可，直到拿到令牌或耗尽预算。
 *
 * <p>给 Kafka 消费路径用。那里不能像 MySQL 路径那样把消息交给调度线程后就返回——offset 必须在
 * 真正推送成功之后才提交，所以只能在消费线程上原地等。阻塞消费线程本身就是最自然的反压：
 * 线程不返回，容器就不会再去 poll 新消息。
 *
 * @author LQH
 */
@Component
@Slf4j
public class BlockingDispatchGate {

    private final DispatchQuotaService quotaService;
    private final FlowControlProperties properties;

    private Clock clock = Clock.systemUTC();

    public BlockingDispatchGate(DispatchQuotaService quotaService,
            FlowControlProperties properties) {
        this.quotaService = quotaService;
        this.properties = properties;
    }

    /**
     * @return {@code true} 表示拿到许可；{@code false} 表示预算内没拿到，消息应回到队列稍后再试
     */
    public boolean awaitPermit(DispatchContext ctx) {
        long deadline = clock.millis() + properties.getKafka().getMaxWaitMillis();
        while (true) {
            AcquireResult result;
            try {
                result = quotaService.acquire(ctx);
            } catch (QuotaUnavailableException e) {
                // fail-closed：配额服务挂了就停发，消息留在 Kafka 里等恢复，不能放任流量打穿供应商。
                log.error("配额服务不可用，暂停 Kafka 推送 msgChannel={}", ctx.channel(), e);
                return false;
            }
            if (result.granted()) {
                return true;
            }
            long remaining = deadline - clock.millis();
            if (remaining <= 0L) {
                return false;
            }
            long park = Math.min(Math.min(result.retryAfterMillis(),
                    properties.getScheduler().getMaxParkMillis()), remaining);
            try {
                Thread.sleep(Math.max(1L, park));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
