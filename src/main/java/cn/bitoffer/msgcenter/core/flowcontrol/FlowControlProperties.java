package cn.bitoffer.msgcenter.core.flowcontrol;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 消费侧流量治理配置。
 *
 * <p>三层配额（平台 / 渠道 / 租户）与优先级权重都放在配置里，不写死在代码中：渠道配额取决于
 * 供应商合同，租户配额取决于业务协商，两者都会在不发版的情况下调整。
 *
 * @author LQH
 */
@Component
@ConfigurationProperties(prefix = "msgcenter.flow-control")
public class FlowControlProperties {

    /** 中台自身的总发送速率，保护线程池、Redis 和数据库。 */
    private Limit platform = new Limit(1000, 2000);

    /** 渠道供应商硬限额，key 是渠道号：1=邮件 2=短信 3=飞书。任何优先级都不得突破。 */
    private Map<Integer, Limit> channels = new LinkedHashMap<>();

    /** 未单独配置的租户走这个默认配额。 */
    private Limit tenantDefault = new Limit(200, 400);

    /** 精确到 {@code tenantId:sourceId:channel} 的租户配额覆盖。 */
    private Map<String, Limit> tenants = new LinkedHashMap<>();

    private Weights weights = new Weights();

    private Scheduler scheduler = new Scheduler();

    private Suppression suppression = new Suppression();

    private Admission admission = new Admission();

    private Kafka kafka = new Kafka();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public Admission getAdmission() {
        return admission;
    }

    public void setAdmission(Admission admission) {
        this.admission = admission;
    }

    public Limit getPlatform() {
        return platform;
    }

    public void setPlatform(Limit platform) {
        this.platform = platform;
    }

    public Map<Integer, Limit> getChannels() {
        return channels;
    }

    public void setChannels(Map<Integer, Limit> channels) {
        this.channels = channels;
    }

    public Limit getTenantDefault() {
        return tenantDefault;
    }

    public void setTenantDefault(Limit tenantDefault) {
        this.tenantDefault = tenantDefault;
    }

    public Map<String, Limit> getTenants() {
        return tenants;
    }

    public void setTenants(Map<String, Limit> tenants) {
        this.tenants = tenants;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public Suppression getSuppression() {
        return suppression;
    }

    public void setSuppression(Suppression suppression) {
        this.suppression = suppression;
    }

    /**
     * 单个令牌桶的速率与突发容量。
     *
     * @author LQH
     */
    public static class Limit {

        private double qps;

        private double burst;

        public Limit() {
        }

        public Limit(double qps, double burst) {
            this.qps = qps;
            this.burst = burst;
        }

        public double getQps() {
            return qps;
        }

        public void setQps(double qps) {
            this.qps = qps;
        }

        public double getBurst() {
            return burst;
        }

        public void setBurst(double burst) {
            this.burst = burst;
        }
    }

    /**
     * 优先级调度权重。低优先级保留最小份额，避免长期饿死。
     *
     * @author LQH
     */
    public static class Weights {

        private int high = 6;

        private int middle = 3;

        private int low = 1;

        public int getHigh() {
            return high;
        }

        public void setHigh(int high) {
            this.high = high;
        }

        public int getMiddle() {
            return middle;
        }

        public void setMiddle(int middle) {
            this.middle = middle;
        }

        public int getLow() {
            return low;
        }

        public void setLow(int low) {
            this.low = low;
        }
    }

    /**
     * 调度器本地等待区。容量必须有界，超出后靠停止拉取形成反压，而不是把消息堆进 JVM 内存。
     *
     * @author LQH
     */
    public static class Scheduler {

        private int capacityPerPriority = 500;

        /** 令牌不足时最多等多久再重试，避免为一个很远的补充时间挂住整条线程。 */
        private long maxParkMillis = 200L;

        /** 等待区为空时的空转间隔。 */
        private long idleParkMillis = 20L;

        /** Redis 配额不可用时的暂停间隔，期间不做任何真实推送。 */
        private long failClosedParkMillis = 1_000L;

        private int workerThreads = 2;

        /**
         * 一条消息最多在等待区停留多久。
         *
         * <p>必须明显小于 stale-processing 的回收阈值：消息在等待区排队时数据库里还是 PROCESSING，
         * 一旦被回收器判定为"卡住的消息"改回 PENDING，就会有另一个节点同时持有同一条消息，形成
         * 重复发送。超时后主动还回 PENDING，让数据库始终是唯一的归属权来源。
         */
        private long maxHoldMillis = 60_000L;

        public long getMaxHoldMillis() {
            return maxHoldMillis;
        }

        public void setMaxHoldMillis(long maxHoldMillis) {
            this.maxHoldMillis = maxHoldMillis;
        }

        public int getCapacityPerPriority() {
            return capacityPerPriority;
        }

        public void setCapacityPerPriority(int capacityPerPriority) {
            this.capacityPerPriority = capacityPerPriority;
        }

        public long getMaxParkMillis() {
            return maxParkMillis;
        }

        public void setMaxParkMillis(long maxParkMillis) {
            this.maxParkMillis = maxParkMillis;
        }

        public long getIdleParkMillis() {
            return idleParkMillis;
        }

        public void setIdleParkMillis(long idleParkMillis) {
            this.idleParkMillis = idleParkMillis;
        }

        public long getFailClosedParkMillis() {
            return failClosedParkMillis;
        }

        public void setFailClosedParkMillis(long failClosedParkMillis) {
            this.failClosedParkMillis = failClosedParkMillis;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }
    }

    /**
     * Kafka 消费路径的等待预算。
     *
     * <p>Kafka 模式下拿令牌是在消费线程上同步等的，等待期间容器不会再 poll。这个预算必须明显小于
     * {@code max.poll.interval.ms}（默认 5 分钟），否则 broker 会以为消费者卡死而触发 rebalance。
     *
     * @author LQH
     */
    public static class Kafka {

        private long maxWaitMillis = 60_000L;

        public long getMaxWaitMillis() {
            return maxWaitMillis;
        }

        public void setMaxWaitMillis(long maxWaitMillis) {
            this.maxWaitMillis = maxWaitMillis;
        }
    }

    /**
     * 入口准入水位。这里保护的是中台自身不被压垮，与渠道配额无关——渠道配额已经完全交给消费侧。
     *
     * <p>默认阈值只是一个能跑起来的起点，正式值要用改造后的容量压测结果回填。
     *
     * @author LQH
     */
    public static class Admission {

        private boolean enabled = true;

        /** 超过这个积压量开始拒绝低优先级。 */
        private long softWatermark = 5_000L;

        /** 超过这个积压量连中优先级一起拒绝，只留高优先级。 */
        private long hardWatermark = 15_000L;

        /** 最老待处理消息等待超过这个秒数，等同于到达硬水位。 */
        private long maxOldestAgeSeconds = 300L;

        /** 单次采样的扫描上限，避免过载时采样查询本身变成负担。 */
        private int sampleCap = 20_000;

        /** 拒绝时返回给调用方的 Retry-After 秒数。 */
        private int retryAfterSeconds = 1;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSoftWatermark() {
            return softWatermark;
        }

        public void setSoftWatermark(long softWatermark) {
            this.softWatermark = softWatermark;
        }

        public long getHardWatermark() {
            return hardWatermark;
        }

        public void setHardWatermark(long hardWatermark) {
            this.hardWatermark = hardWatermark;
        }

        public long getMaxOldestAgeSeconds() {
            return maxOldestAgeSeconds;
        }

        public void setMaxOldestAgeSeconds(long maxOldestAgeSeconds) {
            this.maxOldestAgeSeconds = maxOldestAgeSeconds;
        }

        public int getSampleCap() {
            return sampleCap;
        }

        public void setSampleCap(int sampleCap) {
            this.sampleCap = sampleCap;
        }

        public int getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        public void setRetryAfterSeconds(int retryAfterSeconds) {
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /**
     * 用户防打扰。属于业务策略，命中后消息进入 SUPPRESSED 终态，不是失败也不是无限排队。
     *
     * @author LQH
     */
    public static class Suppression {

        private boolean enabled = false;

        private int windowSeconds = 60;

        private int maxPerWindow = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getMaxPerWindow() {
            return maxPerWindow;
        }

        public void setMaxPerWindow(int maxPerWindow) {
            this.maxPerWindow = maxPerWindow;
        }
    }
}
