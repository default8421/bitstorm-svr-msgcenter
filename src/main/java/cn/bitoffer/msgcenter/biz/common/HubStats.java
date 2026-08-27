package cn.bitoffer.msgcenter.biz.common;

import java.util.List;

/**
 * 运行总览指标，全部来自消息记录表。
 *
 * @author LQH
 */
public record HubStats(long todayTotal, long todaySuccess, long todayPending, long todayFailed,
        double successRate, long grandTotal, int connectedSources,
        List<NameCount> byChannel, List<NameCount> bySource) {

    /**
     * 名称与数量。
     *
     * @author LQH
     */
    public record NameCount(String name, long count) {
    }
}
