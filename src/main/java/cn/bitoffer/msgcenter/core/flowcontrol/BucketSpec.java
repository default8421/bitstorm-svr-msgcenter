package cn.bitoffer.msgcenter.core.flowcontrol;

/**
 * 一个令牌桶的限额定义。
 *
 * @param key Redis 中保存该桶状态的键
 * @param ratePerSecond 稳定补充速率（个/秒），必须大于 0，否则该桶永远拿不到令牌
 * @param burst 桶容量，允许的瞬时突发量
 * @author LQH
 */
public record BucketSpec(String key, double ratePerSecond, double burst) {

    public BucketSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("bucket key 不能为空");
        }
    }
}
