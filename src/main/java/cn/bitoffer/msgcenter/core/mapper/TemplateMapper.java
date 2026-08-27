package cn.bitoffer.msgcenter.core.mapper;


import cn.bitoffer.msgcenter.core.model.TemplateModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * TemplateMapper。
 *
 * @author LQH
 */
@Mapper
public interface TemplateMapper {


    void save(@Param("templateModel") TemplateModel templateModel);

    void deleteById(@Param("templateId") String templateId, @Param("tenantId") String tenantId);

    void update(@Param("templateModel") TemplateModel templateModel);

    TemplateModel getTemplateById(@Param("templateId") String templateId);

    TemplateModel getTemplateByIdAndTenant(@Param("templateId") String templateId,
            @Param("tenantId") String tenantId);

    List<TemplateModel> listByTenant(@Param("tenantId") String tenantId, @Param("name") String name);
}
