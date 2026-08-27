package cn.bitoffer.msgcenter.core.service.impl;

import cn.bitoffer.msgcenter.core.config.SendMsgConf;
import cn.bitoffer.msgcenter.core.constant.Constants;
import cn.bitoffer.msgcenter.core.enums.TemplateStatus;
import cn.bitoffer.msgcenter.core.exception.BusinessException;
import cn.bitoffer.msgcenter.core.exception.ErrorCode;
import cn.bitoffer.msgcenter.core.mapper.TemplateMapper;
import cn.bitoffer.msgcenter.core.model.TemplateModel;
import cn.bitoffer.msgcenter.core.service.TemplateService;
import cn.bitoffer.msgcenter.core.utils.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import cn.bitoffer.msgcenter.core.tenant.TenantContext;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * TemplateServiceImpl。
 *
 * @author LQH
 */
@Service
@Slf4j
public class TemplateServiceImpl implements TemplateService {

    @Autowired
    private TemplateMapper templateMapper;

    @Autowired
    SendMsgConf sendMsgConf;

    @Resource
    RedisTemplate<String,String> redisTemplate;

    @Override
    public String CreateTemplate(TemplateModel templateModel) {
        // 校验参数（@Validated(OnCreate.class) 已经在 Controller 边界拦过一次，这里是防御性兜底）
        if(templateModel.getChannel() == null || templateModel.getChannel() == 0){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"校验 chanenl 参数出错");
        }
        //其他参数校验，略

        // 生成模板 ID
        templateModel.setTemplateId(UUID.randomUUID().toString());
        templateModel.setRelTemplateId(UUID.randomUUID().toString());
        templateModel.setStatus(TemplateStatus.TEMPLATE_STATUS_PENDING.getStatus());
        if (StringUtils.isBlank(templateModel.getTenantId())) {
            templateModel.setTenantId(TenantContext.require());
        }

        // 存入数据库
        templateMapper.save(templateModel);
        return templateModel.getTemplateId();
    }

    @Override
    public void DeleteTemplate(String templateID) {
         templateMapper.deleteById(templateID, TenantContext.require());
         evictCache(templateID);
    }

    @Override
    public void UpdateTemplate(TemplateModel templateModel) {
        templateModel.setTenantId(TenantContext.require());
        templateMapper.update(templateModel);
        evictCache(templateModel.getTemplateId());
    }

    /**
     * Cache-Aside 模式要求写操作时主动失效缓存，不能只靠 TTL 兜底：否则更新/删除模板后的这段
     * TTL 窗口内，GetTemplateWithCache 还会把旧内容（甚至已删除的模板）当作有效模板返回。
     */
    private void evictCache(String templateID) {
        redisTemplate.delete(Constants.REDIS_KEY_TEMPLATE + templateID);
    }

    @Override
    public TemplateModel GetTemplate(String templateID) {
        return templateMapper.getTemplateById(templateID);
    }

    @Override
    public TemplateModel GetTemplateWithCache(String templateID) {
        String templateCacheKey = Constants.REDIS_KEY_TEMPLATE+templateID;
        String cacheTp = redisTemplate.opsForValue().get(templateCacheKey);
        TemplateModel tp = null;
        if(!StringUtils.isEmpty(cacheTp) && sendMsgConf.isOpenCache()){
            tp = JSONUtil.parseObject(cacheTp,TemplateModel.class);
            if(tp != null){
                return tp;
            }
        }

        // 从数据库获取
        tp = templateMapper.getTemplateById(templateID);

        // 只缓存真实存在的模板；缓存 null 会往 Redis 写入字符串 "null" 且无意义，还会掩盖“模板不存在”。
        if(tp != null){
            redisTemplate.opsForValue().set(templateCacheKey,JSONUtil.toJsonString(tp), Duration.ofSeconds(30));
        }

        return tp;
    }

    @Override
    public List<TemplateModel> listMine(String name) {
        return templateMapper.listByTenant(TenantContext.require(), name);
    }

    @Override
    public TemplateModel getMine(String templateId) {
        return templateMapper.getTemplateByIdAndTenant(templateId, TenantContext.require());
    }
}
