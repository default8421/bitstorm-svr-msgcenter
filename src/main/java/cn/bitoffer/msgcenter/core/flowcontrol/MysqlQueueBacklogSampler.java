package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.mapper.MsgQueueMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Date;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性采样 MySQL 队列的积压量与最老待处理消息年龄。
 *
 * <p>入口每条请求都去查一次积压是不可接受的：那会给本就过载的数据库再加一轮压力。这里用固定
 * 周期采样 + 内存快照，入口只读快照。采样滞后最多一个周期，对水位判断足够。
 *
 * <p>积压量与最老年龄两个信号都要看：积压量说明"堆了多少"，最老年龄说明"最倒霉的消息等了多久"。
 * 只看积压量会漏掉队列不长但完全没被消费的情况。
 *
 * @author LQH
 */
@Component
@ConditionalOnProperty(prefix = "send-msg-conf", name = "mysql-as-mq", havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class MysqlQueueBacklogSampler implements BacklogSnapshotProvider {

    private static final PriorityEnum[] SAMPLED_PRIORITIES = {PriorityEnum.PRIORITY_LOW,
            PriorityEnum.PRIORITY_MIDDLE, PriorityEnum.PRIORITY_HIGH, PriorityEnum.PRIORITY_RETRY};

    private final MsgQueueMapper msgQueueMapper;
    private final FlowControlProperties properties;

    private volatile BacklogSnapshot snapshot = BacklogSnapshot.unknown();
    private Clock clock = Clock.systemUTC();

    public MysqlQueueBacklogSampler(MsgQueueMapper msgQueueMapper, FlowControlProperties properties,
            MeterRegistry meterRegistry) {
        this.msgQueueMapper = msgQueueMapper;
        this.properties = properties;
        Gauge.builder("msgcenter.queue.backlog", this, s -> s.snapshot().pendingCount())
                .description("待处理消息数（采样上限内）")
                .register(meterRegistry);
        Gauge.builder("msgcenter.queue.oldest.age.seconds", this,
                        s -> s.snapshot().oldestPendingAgeMillis() / 1000.0)
                .description("最老待处理消息已等待秒数")
                .register(meterRegistry);
    }

    @Override
    public BacklogSnapshot snapshot() {
        return snapshot;
    }

    @Scheduled(fixedRateString = "${msgcenter.flow-control.admission.sample-interval-millis:5000}")
    public void sample() {
        int cap = properties.getAdmission().getSampleCap();
        long pending = 0L;
        long oldestAgeMillis = 0L;
        long now = clock.millis();
        try {
            for (PriorityEnum priority : SAMPLED_PRIORITIES) {
                String table = Constants.TableNamePre_MsgQueue
                        + PriorityEnum.GetPriorityStr(priority.getPriorty());
                pending += msgQueueMapper.countPendingCapped(table, MsgStatus.Pending.getStatus(),
                        cap);
                Date oldest = msgQueueMapper.oldestPendingCreateTime(table,
                        MsgStatus.Pending.getStatus());
                if (oldest != null) {
                    oldestAgeMillis = Math.max(oldestAgeMillis, now - oldest.getTime());
                }
            }
        } catch (RuntimeException e) {
            // 数据库不可用时把快照标记为不可信，让准入判断放行；此时真正的保护是入队本身会失败并返回 503。
            log.warn("采样队列积压失败，准入水位暂时不可用", e);
            snapshot = BacklogSnapshot.unknown();
            return;
        }
        snapshot = new BacklogSnapshot(pending, Math.max(0L, oldestAgeMillis), true);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
