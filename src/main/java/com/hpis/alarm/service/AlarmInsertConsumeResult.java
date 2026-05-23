package com.hpis.alarm.service;

/**
 * 单条 start 消息在 Spring AMQP consumer batch 链路中的最终业务结果。
 *
 * <p>这个枚举只表达“业务是否可以确认 MQ”。RabbitMQ 的 ack/nack 必须仍由 listener
 * 当前线程执行，批处理服务不能直接持有或操作 Channel，避免跨层确认导致连接状态不可控。</p>
 */
public enum AlarmInsertConsumeResult {

    /** 报警已成功入库，afterCommit push 已注册，MQ 可以 ack。 */
    SUCCESS(true),

    /** 断线 Redis 去重判定为重复，本条无需再入库，MQ 可以 ack。 */
    SKIP(true),

    /** 设备缓存缺失，按当前业务要求记录并丢弃，MQ 可以 ack，避免坏消息无限重投。 */
    DROP(true),

    /** 入库或兜底失败，listener 应 nack 且 requeue=true，交给 MQ 重投。 */
    FAIL(false);

    private final boolean ack;

    AlarmInsertConsumeResult(boolean ack) {
        this.ack = ack;
    }

    /** 返回 true 表示业务已给出终态，不需要 RabbitMQ 再投递该消息。 */
    public boolean shouldAck() {
        return ack;
    }
}
