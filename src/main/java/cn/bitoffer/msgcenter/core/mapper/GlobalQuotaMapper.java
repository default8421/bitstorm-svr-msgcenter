package cn.bitoffer.msgcenter.core.mapper;


import cn.bitoffer.msgcenter.core.model.GlobalQuotaModel;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * GlobalQuotaMapper。
 *
 * @author LQH
 */
@Mapper
public interface GlobalQuotaMapper {

    void save(@Param("globalQuotaModel") GlobalQuotaModel globalQuotaModel);

    GlobalQuotaModel getGlobalQuota(@Param("channel") int channel );
}
