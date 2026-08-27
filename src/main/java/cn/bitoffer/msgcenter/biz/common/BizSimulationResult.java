package cn.bitoffer.msgcenter.biz.common;

import java.util.List;

/**
 * 批量模拟的汇总结果。
 *
 * @param total         提交成功数
 * @param failed        提交失败数
 * @param elapsedMillis 耗时
 * @param lines         各业务线明细
 * @author LQH
 */
public record BizSimulationResult(int total, int failed, long elapsedMillis, List<LineStat> lines) {

    /**
     * 单条业务线本次统计。
     *
     * @author LQH
     */
    public record LineStat(String sourceId, String name, String channel, int submitted, int failed) {
    }
}
