package cn.bitoffer.msgcenter.core.flowcontrol;

/**
 * 队列积压的一次采样。
 *
 * @param available 采样是否可信；不可信时准入判断必须放行，不能拿一个空快照当作"系统很空闲"
 *                  或"系统已过载"
 * @author LQH
 */
public record BacklogSnapshot(long pendingCount, long oldestPendingAgeMillis, boolean available) {

    private static final BacklogSnapshot UNKNOWN = new BacklogSnapshot(0L, 0L, false);

    public static BacklogSnapshot unknown() {
        return UNKNOWN;
    }
}
