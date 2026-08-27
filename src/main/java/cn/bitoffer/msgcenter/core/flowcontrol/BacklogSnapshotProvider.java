package cn.bitoffer.msgcenter.core.flowcontrol;

/**
 * 提供当前队列积压水位。
 *
 * <p>不同队列后端的积压在不同地方（MySQL 在表里，Kafka 在 broker 的 lag 里），准入判断只依赖
 * 这个抽象，不关心具体后端。
 *
 * @author LQH
 */
public interface BacklogSnapshotProvider {

    BacklogSnapshot snapshot();
}
