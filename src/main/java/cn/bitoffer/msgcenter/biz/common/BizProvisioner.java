package cn.bitoffer.msgcenter.biz.common;

import cn.bitoffer.msgcenter.core.enums.TemplateStatus;
import cn.bitoffer.msgcenter.core.mapper.SourceQuotaMapper;
import cn.bitoffer.msgcenter.core.model.SourceQuotaModel;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.service.TemplateService;
import cn.bitoffer.msgcenter.core.tenant.TenantContext;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 为业务线准备模板和限流配额。首次使用时创建，之后走内存缓存。
 *
 * @author LQH
 */
@Component
@Slf4j
public class BizProvisioner {

    private final TemplateService templateService;
    private final SourceQuotaMapper sourceQuotaMapper;
    private final ConcurrentMap<String, String> templateIds = new ConcurrentHashMap<>();

    public BizProvisioner(TemplateService templateService, SourceQuotaMapper sourceQuotaMapper) {
        this.templateService = templateService;
        this.sourceQuotaMapper = sourceQuotaMapper;
    }

    public String templateId(BizSource source) {
        String key = TenantContext.require() + ":" + source.name();
        return templateIds.computeIfAbsent(key, ignored -> provision(source));
    }

    private String provision(BizSource source) {
        ensureQuota(source);

        TemplateModel model = new TemplateModel();
        model.setName(source.templateName());
        model.setSourceId(source.sourceId());
        model.setChannel(source.channel());
        model.setSubject(source.displayName());
        model.setContent(source.templateContent());
        String templateId = templateService.CreateTemplate(model);

        // 内核只接受已启用的模板
        model.setTemplateId(templateId);
        model.setStatus(TemplateStatus.TEMPLATE_STATUS_NORMAL.getStatus());
        templateService.UpdateTemplate(model);

        log.info("业务线[{}]模板已就绪 templateId={} channel={}", source.sourceId(), templateId,
                source.channel());
        return templateId;
    }

    private void ensureQuota(BizSource source) {
        try {
            if (sourceQuotaMapper.getSourceQuota(source.channel(), source.sourceId()) != null) {
                return;
            }
            SourceQuotaModel quota = new SourceQuotaModel();
            quota.setSourceId(source.sourceId());
            quota.setChannel(source.channel());
            quota.setNum(source.quotaPerSecond());
            quota.setUnit(1000);
            sourceQuotaMapper.save(quota);
            log.info("业务线[{}]限流配额已写入 {}/s (channel={})", source.sourceId(),
                    source.quotaPerSecond(), source.channel());
        } catch (Exception e) {
            log.warn("写入业务线[{}]限流配额失败(可忽略): {}", source.sourceId(), e.getMessage());
        }
    }
}
