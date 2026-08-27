package cn.bitoffer.msgcenter.core.consumer;

import cn.bitoffer.common.redis.ReentrantDistributeLock;
import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.consumer.poll.MysqlMsgPollTask;
import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContext;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchContextResolver;
import cn.bitoffer.msgcenter.core.flowcontrol.DispatchTask;
import cn.bitoffer.msgcenter.core.flowcontrol.FairDispatchScheduler;
import cn.bitoffer.msgcenter.core.model.MsgQueueModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MySQL-as-queue consumer path. Enabled only when {@code send-msg-conf.mysql-as-mq=true}
 * (the default). When Kafka is the queue this bean is absent so the two consumer paths never run
 * concurrently and idle-spin against an empty backend.
 */
@Component
@ConditionalOnProperty(prefix = "send-msg-conf", name = "mysql-as-mq", havingValue = "true",
        matchIfMissing = true)
/**
 * MysqlMsgConsumer。
 *
 * @author LQH
 */
@Slf4j
public class MysqlMsgConsumer {

    @Autowired
    MysqlMsgPollTask mysqlMsgPollTask;

    @Autowired
    MysqlMsgClaimService mysqlMsgClaimService;

    @Autowired
    FairDispatchScheduler fairDispatchScheduler;

    @Autowired
    DispatchContextResolver dispatchContextResolver;

    @Autowired
    ReentrantDistributeLock reentrantDistributeLock;

    private static final int LOCK_RETRY_INTERVAL_SECONDS = 10;
    /** Rows stuck in PROCESSING longer than this are reclaimed to PENDING. */
    private static final int STALE_PROCESSING_SECONDS = 120;

    private final Map<PriorityEnum, Boolean> isLeaderMap = new ConcurrentHashMap<>();
    private final Map<PriorityEnum, Long> nextLeaderAttemptMillis = new ConcurrentHashMap<>();

    private Clock clock = Clock.systemUTC();

    @Scheduled(fixedRate = 1000)
    public void consumeLow() {
        consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_LOW,10);
        consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_MIDDLE,30);
        consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_HIGH,60);
        consumeMySQLMsgWithLeaderCheck(PriorityEnum.PRIORITY_RETRY,10);
    }

    /** Periodically reclaims messages left in PROCESSING by a crashed/slow worker. */
    @Scheduled(fixedRate = 30000)
    public void recoverStaleProcessing() {
        for (PriorityEnum priority : PriorityEnum.values()) {
            if (Boolean.TRUE.equals(isLeaderMap.get(priority))) {
                String tableName = Constants.TableNamePre_MsgQueue
                        + PriorityEnum.GetPriorityStr(priority.getPriorty());
                mysqlMsgClaimService.recoverStale(tableName, STALE_PROCESSING_SECONDS);
            }
        }
    }

    void consumeMySQLMsgWithLeaderCheck(PriorityEnum priorityEnum,int pullNum) {
        if (Boolean.TRUE.equals(isLeaderMap.get(priorityEnum))) {
            consumeMySQLMsg(priorityEnum,pullNum);
            return;
        }
        // Backup node: retry leadership acquisition on a time-gated basis WITHOUT blocking the
        // scheduler thread (no Thread.sleep).
        long now = clock.millis();
        long nextAttempt = nextLeaderAttemptMillis.getOrDefault(priorityEnum, 0L);
        if (now < nextAttempt) {
            return;
        }
        nextLeaderAttemptMillis.put(priorityEnum,
                now + LOCK_RETRY_INTERVAL_SECONDS * 1000L);
        if (tryBeLeader(priorityEnum)) {
            log.info("{}优先级消费者从备用节点升级为主节点",
                    PriorityEnum.GetPriorityStr(priorityEnum.getPriorty()));
            isLeaderMap.put(priorityEnum,true);
        }
    }

    private boolean tryBeLeader(PriorityEnum priorityEnum){
        String lockToken = System.currentTimeMillis()+Thread.currentThread().getName();
        boolean ok = reentrantDistributeLock.lockWithDog(PriorityEnum.GetPriorityStr(priorityEnum.getPriorty())+"_MSG_LEADER_CONSUMER_JAVA",
                lockToken, LOCK_RETRY_INTERVAL_SECONDS);
        if(!ok){
            log.warn("timer consumer get lock failed！");
            return false;
        }
        return true;
    }

    private void consumeMySQLMsg(PriorityEnum priority,int pullNum){
        // 1. 根据优先级确定表名
        String tableName = Constants.TableNamePre_MsgQueue+ PriorityEnum.GetPriorityStr(priority.getPriorty());

        // 2. 只领取本地等待区还放得下的量。领多了就等于把积压从 MySQL 搬进 JVM 内存：进程一挂
        //    这些消息要靠 stale-processing 才能回收，中间这段时间用户什么都收不到。
        int capacity = fairDispatchScheduler.remainingCapacity(priority.getPriorty());
        if (capacity <= 0) {
            return;
        }

        // 3. 事务内原子领取一批消息（FOR UPDATE SKIP LOCKED + 守卫更新），避免先查后改的重复消费
        List<MsgQueueModel> msgList = mysqlMsgClaimService.claim(tableName,
                Math.min(pullNum, capacity));
        if(msgList.isEmpty()){
            return;
        }

        // 4. 交给公平调度器排队，真正的发送时机由配额决定
        for (MsgQueueModel dbModel:msgList) {
            SendMsgReq req = new SendMsgReq();
            req.setMsgID(dbModel.getMsgId());
            req.setPriority(dbModel.getPriority());
            req.setTo(dbModel.getTo());
            req.setSubject(dbModel.getSubject());
            req.setTemplateId(dbModel.getTemplateId());

            Map<String,String> templateData = JSONUtil.parseMap(dbModel.getTemplateData(),String.class,String.class);
            req.setTemplateData(templateData);

            submitToScheduler(req);
        }
    }

    /**
     * 模板已被删除等永久性错误直接落终态：这类消息重试多少次都不会成功，让它一直占着队列只会
     * 拖慢真正能发出去的消息。
     */
    private void submitToScheduler(SendMsgReq req) {
        DispatchContext ctx;
        try {
            ctx = dispatchContextResolver.resolve(req);
        } catch (RuntimeException e) {
            log.error("消息无法解析投递上下文，标记为最终失败 msgId={}", req.getMsgID(), e);
            mysqlMsgPollTask.markPermanentlyFailed(req);
            return;
        }
        if (!fairDispatchScheduler.offer(
                new DispatchTask(req, ctx, System.currentTimeMillis()))) {
            // 领取和入等待区之间容量被别的线程用光了。放回 PENDING 让下一轮重新领取，
            // 不然这条消息要等 stale-processing 超时才能被再次处理。
            mysqlMsgPollTask.releaseToPending(req);
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setLeader(PriorityEnum priority, boolean leader) {
        isLeaderMap.put(priority, leader);
    }
}
