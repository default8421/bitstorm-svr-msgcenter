package cn.bitoffer.msgcenter.core.flowcontrol;

import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.model.dto.SendMsgReq;
import cn.bitoffer.msgcenter.core.service.TemplateService;
import org.springframework.stereotype.Component;

/**
 * 从队列里的消息还原出治理需要的租户、来源和渠道。
 *
 * <p>这三个维度都能从模板拿到，而模板本身带 Redis 缓存，所以不需要往队列表里冗余字段，也就不
 * 需要为这次改造做数据迁移。
 *
 * @author LQH
 */
@Component
public class DispatchContextResolver {

    private final TemplateService templateService;

    public DispatchContextResolver(TemplateService templateService) {
        this.templateService = templateService;
    }

    public DispatchContext resolve(SendMsgReq req) {
        TemplateModel tp = templateService.GetTemplateWithCache(req.getTemplateId());
        if (tp == null) {
            // 模板在入队后被删掉了。重试多少次都不会变好，交给上层走失败终态而不是无限重投。
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "模板不存在，无法投递 templateId:" + req.getTemplateId());
        }
        return new DispatchContext(tp.getTenantId(), tp.getSourceId(),
                tp.getChannel() == null ? 0 : tp.getChannel(), req.getPriority(), req.getTo());
    }
}
