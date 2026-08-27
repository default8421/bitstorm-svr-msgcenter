package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.enums.PriorityEnum;
import cn.bitoffer.msgcenter.core.exception.OverloadException;
import cn.bitoffer.msgcenter.core.flowcontrol.FlowControlProperties.Admission;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 入口准入判断。
 *
 * <p>职责边界很窄：只回答"中台现在还能不能可靠收下这条消息"。渠道供应商配额、租户配额、发送
 * 节奏都不在这里，全部交给消费侧的 {@link DispatchQuotaService}。入口不再因为业务限额拒绝或
 * 延迟消息——那正是旧方案把"受理"和"发送速度"混在一起导致的问题。
 *
 * <p>拒绝按优先级分级：软水位先挡掉低优先级的营销类消息，硬水位再挡掉中优先级，高优先级
 * （验证码、告警）只要队列还写得进去就一直受理。
 *
 * @author LQH
 */
@Component
@Slf4j
public class AdmissionGuard {

    private final ObjectProvider<BacklogSnapshotProvider> backlogProvider;
    private final FlowControlProperties properties;
    private final MeterRegistry meterRegistry;

    public AdmissionGuard(ObjectProvider<BacklogSnapshotProvider> backlogProvider,
            FlowControlProperties properties, MeterRegistry meterRegistry) {
        this.backlogProvider = backlogProvider;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * @throws OverloadException 当前水位不允许受理这个优先级的新消息
     */
    public void check(int priority) {
        Admission admission = properties.getAdmission();
        if (!admission.isEnabled()) {
            return;
        }
        BacklogSnapshotProvider provider = backlogProvider.getIfAvailable();
        if (provider == null) {
            return;
        }
        BacklogSnapshot snapshot = provider.snapshot();
        if (!snapshot.available()) {
            return;
        }

        boolean hardOverload = snapshot.pendingCount() >= admission.getHardWatermark()
                || snapshot.oldestPendingAgeMillis()
                        >= admission.getMaxOldestAgeSeconds() * 1000L;
        boolean softOverload = snapshot.pendingCount() >= admission.getSoftWatermark();

        if (priority == PriorityEnum.PRIORITY_HIGH.getPriorty()) {
            return;
        }
        boolean rejected = hardOverload
                || (softOverload && priority == PriorityEnum.PRIORITY_LOW.getPriorty());
        if (!rejected) {
            return;
        }

        meterRegistry.counter("msgcenter.admission.rejected",
                "priority", PriorityEnum.GetPriorityStr(priority),
                "level", hardOverload ? "hard" : "soft").increment();
        throw new OverloadException(
                "平台积压已达水位，请稍后重试。当前积压=" + snapshot.pendingCount()
                        + "，最老消息等待=" + snapshot.oldestPendingAgeMillis() / 1000 + "s",
                admission.getRetryAfterSeconds());
    }
}
