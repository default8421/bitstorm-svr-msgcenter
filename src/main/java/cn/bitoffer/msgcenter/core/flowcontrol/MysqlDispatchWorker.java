package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.consumer.poll.MysqlMsgPollTask;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.redis.QuotaUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * 把"已领取的消息"变成"真正发出去的消息"的那道闸门。
 *
 * <p>循环只做三件事：按公平调度取下一条、拿到三层令牌、交给推送线程池。任何一步不满足，消息都
 * 原样放回等待区，既不算失败，也不增加重试次数——这是整套改造最关键的语义：限流不是失败。
 *
 * <p>只在 MySQL 作为队列后端时启用；Kafka 路径的节奏由消费线程自己在 {@code KafkaMsgConsumer}
 * 里控制，因为那里必须保证"推送成功后才提交 offset"，不能把消息交给另一个线程后就提交。
 *
 * @author LQH
 */
@Component
@ConditionalOnProperty(prefix = "send-msg-conf", name = "mysql-as-mq", havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class MysqlDispatchWorker {

    private final FairDispatchScheduler scheduler;
    private final DispatchQuotaService quotaService;
    private final RecipientFrequencyPolicy recipientFrequencyPolicy;
    private final MysqlMsgPollTask mysqlMsgPollTask;
    private final FlowControlProperties properties;
    private final MeterRegistry meterRegistry;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public MysqlDispatchWorker(FairDispatchScheduler scheduler, DispatchQuotaService quotaService,
            RecipientFrequencyPolicy recipientFrequencyPolicy, MysqlMsgPollTask mysqlMsgPollTask,
            FlowControlProperties properties, MeterRegistry meterRegistry) {
        this.scheduler = scheduler;
        this.quotaService = quotaService;
        this.recipientFrequencyPolicy = recipientFrequencyPolicy;
        this.mysqlMsgPollTask = mysqlMsgPollTask;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running) {
            return;
        }
        running = true;
        int threads = Math.max(1, properties.getScheduler().getWorkerThreads());
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(this::runLoop, "Dispatch-" + i);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
        log.info("发送调度线程已启动，线程数={}", threads);
    }

    @PreDestroy
    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
    }

    private void runLoop() {
        while (running) {
            try {
                if (!dispatchOnce()) {
                    park(properties.getScheduler().getIdleParkMillis());
                }
            } catch (RuntimeException e) {
                // 单条消息的意外异常不能让整条调度线程退出，否则积压会永远发不出去。
                log.error("发送调度循环出现未预期异常", e);
                park(properties.getScheduler().getIdleParkMillis());
            }
        }
    }

    /**
     * @return {@code true} 表示这一轮真的推进了一条消息，调用方可以立刻继续下一轮
     */
    boolean dispatchOnce() {
        DispatchTask task = scheduler.poll();
        if (task == null) {
            return false;
        }

        if (heldTooLong(task)) {
            log.warn("消息在等待区停留过久，交还队列重新领取 msgId={}", task.req().getMsgID());
            mysqlMsgPollTask.releaseToPending(task.req());
            meterRegistry.counter("msgcenter.dispatch.released", "reason", "held_too_long")
                    .increment();
            return true;
        }

        if (recipientFrequencyPolicy.shouldSuppress(task.ctx(), task.req().getTemplateId())) {
            mysqlMsgPollTask.markSuppressed(task.req(), task.ctx());
            return true;
        }

        AcquireResult result;
        try {
            result = quotaService.acquire(task.ctx());
        } catch (QuotaUnavailableException e) {
            // fail-closed：宁可暂停发送，也不能在限流组件失灵时把供应商配额打穿。消息还在 MySQL 里，
            // 崩溃了也有 stale-processing 兜底。
            log.error("配额服务不可用，暂停真实推送，消息保留在队列", e);
            scheduler.requeue(task);
            meterRegistry.counter("msgcenter.dispatch.paused", "reason", "quota_unavailable")
                    .increment();
            park(properties.getScheduler().getFailClosedParkMillis());
            return true;
        }

        if (!result.granted()) {
            scheduler.requeue(task);
            park(Math.min(result.retryAfterMillis(),
                    properties.getScheduler().getMaxParkMillis()));
            return true;
        }

        recordWait(task);
        try {
            mysqlMsgPollTask.asyncHandleMsg(task.req());
        } catch (TaskRejectedException e) {
            // 推送线程池满了：放回等待区稍后再试，绝不能丢。令牌已经扣掉，代价只是这一刻少发一条。
            log.warn("推送线程池已满，消息放回等待区 msgId={}", task.req().getMsgID());
            scheduler.requeue(task);
            park(properties.getScheduler().getIdleParkMillis());
        }
        return true;
    }

    private boolean heldTooLong(DispatchTask task) {
        return task.enqueuedAtMillis() > 0L
                && System.currentTimeMillis() - task.enqueuedAtMillis()
                        > properties.getScheduler().getMaxHoldMillis();
    }

    private void recordWait(DispatchTask task) {
        if (task.enqueuedAtMillis() <= 0L) {
            return;
        }
        meterRegistry.timer("msgcenter.dispatch.wait",
                        "priority", PriorityEnum.GetPriorityStr(task.ctx().priority()))
                .record(System.currentTimeMillis() - task.enqueuedAtMillis(),
                        TimeUnit.MILLISECONDS);
    }

    private void park(long millis) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
