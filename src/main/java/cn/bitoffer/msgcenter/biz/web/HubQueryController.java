package cn.bitoffer.msgcenter.biz.web;

import cn.bitoffer.msgcenter.biz.common.HubStats;
import cn.bitoffer.msgcenter.biz.common.HubStatsService;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行总览与消息记录。未登录返回空数据；登录后只返回当前租户的数据。
 *
 * @author LQH
 */
@RestController
@RequestMapping("/api/hub")
public class HubQueryController {

    private final HubStatsService hubStatsService;
    private final MsgRecordMapper msgRecordMapper;

    public HubQueryController(HubStatsService hubStatsService, MsgRecordMapper msgRecordMapper) {
        this.hubStatsService = hubStatsService;
        this.msgRecordMapper = msgRecordMapper;
    }

    @GetMapping("/stats")
    public HubStats stats() {
        return hubStatsService.stats();
    }

    @GetMapping("/messages")
    public List<Map<String, Object>> messages(@RequestParam(defaultValue = "50") int limit) {
        String tenantId = TenantContext.current();
        if (tenantId == null) {
            return List.of();
        }
        int capped = Math.max(1, Math.min(limit, 200));
        return msgRecordMapper.recentMessages(tenantId, capped);
    }
}
