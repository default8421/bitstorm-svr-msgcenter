package cn.bitoffer.msgcenter.core.service;

import cn.bitoffer.msgcenter.core.model.TemplateModel;
import java.util.List;

/**
 * TemplateService。
 *
 * @author LQH
 */
public interface TemplateService {

    String CreateTemplate(TemplateModel templateModel);

    void DeleteTemplate(String templateID);

    void UpdateTemplate(TemplateModel templateModel);

    TemplateModel GetTemplate(String templateID);

    TemplateModel GetTemplateWithCache(String templateID);

    List<TemplateModel> listMine(String name);

    TemplateModel getMine(String templateId);
}
