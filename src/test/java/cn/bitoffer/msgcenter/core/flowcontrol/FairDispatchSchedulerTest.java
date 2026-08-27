package cn.bitoffer.msgcenter.core.flowcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * FairDispatchSchedulerTest。
 *
 * @author LQH
 */
class FairDispatchSchedulerTest {

    private final FlowControlProperties properties = new FlowControlProperties();
    private final FairDispatchScheduler scheduler =
            new FairDispatchScheduler(properties, new SimpleMeterRegistry());

    @Test
    void keepsServingLowPriorityWhileHighPriorityIsBacklogged() {
        fill(PriorityEnum.PRIORITY_HIGH.getPriorty(), "acme", 100);
        fill(PriorityEnum.PRIORITY_LOW.getPriorty(), "acme", 100);

        List<Integer> served = drain(100);

        // 严格优先级会让低优先级在积压期间彻底饿死；加权轮询必须始终留出最小份额。
        assertThat(served).contains(PriorityEnum.PRIORITY_LOW.getPriorty());
    }

    @Test
    void splitsCapacityBetweenPrioritiesRoughlyByConfiguredWeight() {
        properties.getWeights().setHigh(6);
        properties.getWeights().setMiddle(3);
        properties.getWeights().setLow(1);
        FairDispatchScheduler weighted =
                new FairDispatchScheduler(properties, new SimpleMeterRegistry());
        fillOn(weighted, PriorityEnum.PRIORITY_HIGH.getPriorty(), "acme", 200);
        fillOn(weighted, PriorityEnum.PRIORITY_MIDDLE.getPriorty(), "acme", 200);
        fillOn(weighted, PriorityEnum.PRIORITY_LOW.getPriorty(), "acme", 200);

        List<Integer> served = drainFrom(weighted, 100);

        assertThat(count(served, PriorityEnum.PRIORITY_HIGH)).isEqualTo(60);
        assertThat(count(served, PriorityEnum.PRIORITY_MIDDLE)).isEqualTo(30);
        assertThat(count(served, PriorityEnum.PRIORITY_LOW)).isEqualTo(10);
    }

    @Test
    void interleavesHighPriorityInsteadOfEmittingItAsOneBurst() {
        fill(PriorityEnum.PRIORITY_HIGH.getPriorty(), "acme", 50);
        fill(PriorityEnum.PRIORITY_LOW.getPriorty(), "acme", 50);

        List<Integer> served = drain(20);

        // 权重是 6:1，所以前 20 条里低优先级至少要出现一次，而不是等 6 条高优先级一次性发完。
        assertThat(served.subList(0, 14)).contains(PriorityEnum.PRIORITY_LOW.getPriorty());
    }

    @Test
    void stopsOneNoisyTenantFromCrowdingOutTheOthers() {
        fill(PriorityEnum.PRIORITY_LOW.getPriorty(), "noisy", 100);
        fill(PriorityEnum.PRIORITY_LOW.getPriorty(), "quiet", 2);

        List<String> tenants = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tenants.add(scheduler.poll().ctx().tenantId());
        }

        assertThat(tenants).filteredOn("quiet"::equals).hasSize(2);
    }

    @Test
    void refusesNewWorkOnceTheWaitingAreaIsFullSoBacklogStaysInTheQueue() {
        properties.getScheduler().setCapacityPerPriority(3);

        fill(PriorityEnum.PRIORITY_LOW.getPriorty(), "acme", 3);

        assertThat(scheduler.remainingCapacity(PriorityEnum.PRIORITY_LOW.getPriorty())).isZero();
        assertThat(scheduler.offer(task(PriorityEnum.PRIORITY_LOW.getPriorty(), "acme"))).isFalse();
    }

    @Test
    void putsAThrottledMessageBackAheadOfItsTenantsLaterMessages() {
        scheduler.offer(task(PriorityEnum.PRIORITY_LOW.getPriorty(), "acme", "first"));
        scheduler.offer(task(PriorityEnum.PRIORITY_LOW.getPriorty(), "acme", "second"));

        DispatchTask first = scheduler.poll();
        scheduler.requeue(first);

        assertThat(scheduler.poll().req().getMsgID()).isEqualTo("first");
    }

    @Test
    void reportsNothingToDoOnAnEmptyScheduler() {
        assertThat(scheduler.poll()).isNull();
    }

    private void fill(int priority, String tenant, int count) {
        fillOn(scheduler, priority, tenant, count);
    }

    private static void fillOn(FairDispatchScheduler target, int priority, String tenant,
            int count) {
        for (int i = 0; i < count; i++) {
            target.offer(task(priority, tenant, tenant + "-" + priority + "-" + i));
        }
    }

    private List<Integer> drain(int count) {
        return drainFrom(scheduler, count);
    }

    private static List<Integer> drainFrom(FairDispatchScheduler target, int count) {
        List<Integer> served = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            DispatchTask task = target.poll();
            if (task == null) {
                break;
            }
            served.add(task.ctx().priority());
        }
        return served;
    }

    private static long count(List<Integer> served, PriorityEnum priority) {
        return served.stream().filter(p -> p == priority.getPriorty()).count();
    }

    private static DispatchTask task(int priority, String tenant) {
        return task(priority, tenant, "msg");
    }

    private static DispatchTask task(int priority, String tenant, String msgId) {
        SendMsgReq req = new SendMsgReq();
        req.setMsgID(msgId);
        req.setPriority(priority);
        return new DispatchTask(req,
                new DispatchContext(tenant, "billing", 2, priority, "to@example.com"), 0L);
    }
}
