package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 两级公平调度：优先级之间按权重，优先级内部按租户轮询。
 *
 * <p>优先级之间用平滑加权轮询（smooth weighted round-robin）而不是"先发完高优先级再发中优先级"。
 * 严格优先级在积压时会让低优先级永久饿死；平滑加权则保证每个优先级都拿到 {@code 权重/总权重}
 * 的份额，同时把高优先级的份额均匀铺开，而不是先来一串高优先级的突发。
 *
 * <p>选取候选时只统计非空的优先级，空闲份额自动被其他优先级借走，不会浪费。
 *
 * <p>等待区必须有界：{@link #offer} 返回 {@code false} 就是给上游的反压信号，让消息继续留在
 * MySQL 或 Kafka 里，而不是堆进 JVM 堆内存——积压转移到 JVM 里既丢得掉，又看不见。
 *
 * @author LQH
 */
@Component
public class FairDispatchScheduler {

    private final FlowControlProperties properties;
    private final Map<Integer, PriorityLane> lanes = new LinkedHashMap<>();

    public FairDispatchScheduler(FlowControlProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        for (PriorityEnum priority : PriorityEnum.values()) {
            PriorityLane lane = new PriorityLane(weightOf(priority.getPriorty()));
            lanes.put(priority.getPriorty(), lane);
            Gauge.builder("msgcenter.dispatch.queue.depth", lane, PriorityLane::size)
                    .tag("priority", PriorityEnum.GetPriorityStr(priority.getPriorty()))
                    .description("本地等待区深度")
                    .register(meterRegistry);
        }
    }

    /**
     * @return {@code false} 表示该优先级等待区已满，调用方必须停止领取新消息
     */
    public synchronized boolean offer(DispatchTask task) {
        PriorityLane lane = laneFor(task.ctx().priority());
        if (lane.size() >= properties.getScheduler().getCapacityPerPriority()) {
            return false;
        }
        lane.offer(task.ctx().tenantId(), task);
        return true;
    }

    /** 该优先级还能再领多少条消息。 */
    public synchronized int remainingCapacity(int priority) {
        return Math.max(0,
                properties.getScheduler().getCapacityPerPriority() - laneFor(priority).size());
    }

    /**
     * 取出下一条待发送消息，没有则返回 {@code null}。
     */
    public synchronized DispatchTask poll() {
        PriorityLane selected = null;
        int totalWeight = 0;
        int best = Integer.MIN_VALUE;
        for (Map.Entry<Integer, PriorityLane> entry : lanes.entrySet()) {
            PriorityLane lane = entry.getValue();
            if (lane.size() == 0) {
                continue;
            }
            totalWeight += lane.weight;
            lane.currentWeight += lane.weight;
            if (lane.currentWeight > best) {
                best = lane.currentWeight;
                selected = lane;
            }
        }
        if (selected == null) {
            return null;
        }
        selected.currentWeight -= totalWeight;
        return selected.pollRoundRobin();
    }

    /**
     * 把没拿到令牌的消息放回队首。
     *
     * <p>放回队首而不是队尾，是为了保持同一租户内部的先进先出：被限流不是这条消息的错，不该让它
     * 排到后面去。租户之间的顺序由轮询游标继续推进，不会卡在同一个租户上。
     */
    public synchronized void requeue(DispatchTask task) {
        laneFor(task.ctx().priority()).requeue(task.ctx().tenantId(), task);
    }

    public synchronized int depth(int priority) {
        return laneFor(priority).size();
    }

    private PriorityLane laneFor(int priority) {
        PriorityLane lane = lanes.get(priority);
        return lane != null ? lane : lanes.get(PriorityEnum.PRIORITY_LOW.getPriorty());
    }

    private int weightOf(int priority) {
        FlowControlProperties.Weights weights = properties.getWeights();
        if (priority == PriorityEnum.PRIORITY_HIGH.getPriorty()) {
            return Math.max(1, weights.getHigh());
        }
        if (priority == PriorityEnum.PRIORITY_MIDDLE.getPriorty()) {
            return Math.max(1, weights.getMiddle());
        }
        // 重试消息与低优先级同权重：重试代表的是已经失败过一次的流量，让它和新消息抢同样的
        // 份额会在下游抖动时放大成重试风暴。
        return Math.max(1, weights.getLow());
    }

    /**
     * 单个优先级的等待区，内部按租户分队列轮询。
     *
     * @author LQH
     */
    private static final class PriorityLane {

        private final int weight;
        private final Map<String, Deque<DispatchTask>> byTenant = new LinkedHashMap<>();
        private int currentWeight;
        private int size;

        private PriorityLane(int weight) {
            this.weight = weight;
        }

        private int size() {
            return size;
        }

        private void offer(String tenantId, DispatchTask task) {
            byTenant.computeIfAbsent(tenantId, k -> new ArrayDeque<>()).addLast(task);
            size++;
        }

        private void requeue(String tenantId, DispatchTask task) {
            byTenant.computeIfAbsent(tenantId, k -> new ArrayDeque<>()).addFirst(task);
            size++;
        }

        /**
         * 取出下一个租户队首的消息。
         *
         * <p>LinkedHashMap 保持插入顺序，取完后把该租户重新放到末尾就实现了轮询：一个租户猛灌
         * 消息时，它每一轮也只能拿到一条，其他租户的消息不会被压在后面。
         */
        private DispatchTask pollRoundRobin() {
            var iterator = byTenant.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Deque<DispatchTask>> entry = iterator.next();
                Deque<DispatchTask> queue = entry.getValue();
                if (queue.isEmpty()) {
                    iterator.remove();
                    continue;
                }
                DispatchTask task = queue.pollFirst();
                size--;
                iterator.remove();
                if (!queue.isEmpty()) {
                    byTenant.put(entry.getKey(), queue);
                }
                return task;
            }
            return null;
        }
    }
}
