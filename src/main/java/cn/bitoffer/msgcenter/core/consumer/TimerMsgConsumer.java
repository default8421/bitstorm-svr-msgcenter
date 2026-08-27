package cn.bitoffer.msgcenter.core.consumer;

import cn.bitoffer.common.redis.ReentrantDistributeLock;
import cn.bitoffer.msgcenter.core.consumer.poll.TimerMsgResendPollTask;
import cn.bitoffer.msgcenter.core.model.MsgQueueTimerModel;
import cn.bitoffer.msgcenter.core.redis.TimerMsgCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Date;
import java.util.List;

/**
 * TimerMsgConsumer。
 *
 * @author LQH
 */
@Component
@Slf4j
public class TimerMsgConsumer {

    @Autowired
    TimerMsgCache timerMsgCache;

    @Autowired
    TimerMsgResendPollTask timerMsgResendPollTask;

    @Autowired
    TimerMsgClaimService timerMsgClaimService;

    @Autowired
    ReentrantDistributeLock reentrantDistributeLock;

    private volatile boolean isLeader = false;

    private static final int LOCK_TIMER_RETRY_INTERVAL_SECONDS = 10;
    /** Upper bound on rows claimed per tick so one poll cannot pull an unbounded batch. */
    private static final int TIMER_PULL_NUM = 200;
    /** Rows stuck in PROCESSING longer than this are reclaimed to PENDING. */
    private static final int STALE_PROCESSING_SECONDS = 120;

    private long nextLeaderAttemptMillis = 0L;

    private Clock clock = Clock.systemUTC();

    @Scheduled(fixedRate = 100)
    public void consume() {
        if (isLeader){
            consumeTimerMsgs();
            return;
        }
        // 作为备用节点，按时间间隔尝试获取锁，但绝不在调度线程内 Thread.sleep 阻塞。
        long now = clock.millis();
        if (now < nextLeaderAttemptMillis) {
            return;
        }
        nextLeaderAttemptMillis = now + LOCK_TIMER_RETRY_INTERVAL_SECONDS * 1000L;
        isLeader = tryBeLeader();
        if (isLeader) {
            log.info("定时消费者从备用节点升级为主节点");
        }
    }

    private boolean tryBeLeader(){
        String lockToken = System.currentTimeMillis()+Thread.currentThread().getName();
        boolean ok = reentrantDistributeLock.lockWithDog("TIMER_MSG_LEADER_CONSUMER_JAVA",
                lockToken, LOCK_TIMER_RETRY_INTERVAL_SECONDS);
        if(!ok){
            log.warn("timer consumer get lock failed！");
            return false;
        }
        return true;
    }
    
    private void consumeTimerMsgs(){
        // 1. 从Redis获取是否存在到点的 时间点
        List<String>  times = timerMsgCache.getOnTimePointsFromCache();
        if(times == null || times.size() == 0){
            return;
        }

        // 2. 事务内原子领取一批到点消息（FOR UPDATE SKIP LOCKED + 守卫更新 Pending->Processing），
        //    避免先查后改导致的重复消费；正确性来自原子领取而非仅依赖 leader 选举。
        List<MsgQueueTimerModel> onTimeMsgs =
                timerMsgClaimService.claim(new Date().getTime(), TIMER_PULL_NUM);
        if(onTimeMsgs.isEmpty()){
            return;
        }

        // 3. 遍历挨个处理到点消息
        for (MsgQueueTimerModel dbModel:onTimeMsgs) {
            // 线程池异步处理
            timerMsgResendPollTask.asyncHandleMsg(dbModel.getReq());
        }
    }

    /** Periodically reclaims timer rows left in PROCESSING by a crashed/slow worker. */
    @Scheduled(fixedRate = 30000)
    public void recoverStaleProcessing() {
        if (!isLeader) {
            return;
        }
        timerMsgClaimService.recoverStale(STALE_PROCESSING_SECONDS);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setLeader(boolean leader) {
        this.isLeader = leader;
    }
}
