package cn.bitoffer.msgcenter.biz.common;

import cn.bitoffer.msgcenter.core.enums.MsgStatus;
import cn.bitoffer.msgcenter.core.mapper.MsgRecordMapper;
import cn.bitoffer.msgcenter.core.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 从消息记录表聚合运行总览。
 *
 * @author LQH
 */
@Service
public class HubStatsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MsgRecordMapper msgRecordMapper;

    public HubStatsService(MsgRecordMapper msgRecordMapper) {
        this.msgRecordMapper = msgRecordMapper;
    }

    public HubStats stats() {
        String tenantId = TenantContext.current();
        if (tenantId == null) {
            return empty();
        }
        String todayStart = LocalDate.now(ZONE).atStartOfDay().format(FMT);

        long success = 0;
        long pending = 0;
        long failed = 0;
        long total = 0;
        for (Map<String, Object> row : msgRecordMapper.statusCountsSince(tenantId, todayStart)) {
            int status = asInt(row.get("status"));
            long cnt = asLong(row.get("cnt"));
            total += cnt;
            if (status == MsgStatus.Succeed.getStatus()) {
                success += cnt;
            } else if (status == MsgStatus.Failed.getStatus()) {
                failed += cnt;
            } else {
                pending += cnt;
            }
        }
        double successRate = total > 0 ? Math.round((double) success / total * 10000) / 10000.0 : 0.0;

        List<HubStats.NameCount> byChannel = new ArrayList<>();
        for (Map<String, Object> row : msgRecordMapper.channelCountsSince(tenantId, todayStart)) {
            byChannel.add(new HubStats.NameCount(BizSource.channelName(asInt(row.get("channel"))),
                    asLong(row.get("cnt"))));
        }

        List<HubStats.NameCount> bySource = new ArrayList<>();
        for (Map<String, Object> row : msgRecordMapper.sourceCountsSince(tenantId, todayStart)) {
            String sourceId = row.get("sourceId") == null ? null : String.valueOf(row.get("sourceId"));
            bySource.add(new HubStats.NameCount(BizSource.displayNameOf(sourceId), asLong(row.get("cnt"))));
        }

        return new HubStats(total, success, pending, failed, successRate,
                msgRecordMapper.countAll(tenantId), BizSource.values().length, byChannel, bySource);
    }

    private static HubStats empty() {
        return new HubStats(0, 0, 0, 0, 0.0, 0, 0, List.of(), List.of());
    }

    private static int asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : Integer.parseInt(String.valueOf(o));
    }

    private static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : Long.parseLong(String.valueOf(o));
    }
}
