package cn.bitoffer.msgcenter.core.mapper;


import cn.bitoffer.msgcenter.core.model.GlobalQuotaModel;
import cn.bitoffer.msgcenter.core.model.SourceQuotaModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * SourceQuotaMapper。
 *
 * @author LQH
 */
@Mapper
public interface SourceQuotaMapper {

    void save(@Param("sourceQuotaModel") SourceQuotaModel sourceQuotaModel);

    SourceQuotaModel getSourceQuota(@Param("channel") int channel,@Param("sourceId") String sourceId);
}
