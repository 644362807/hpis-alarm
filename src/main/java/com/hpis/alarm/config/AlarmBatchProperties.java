package com.hpis.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 报警批量链路配置。
 *
 * <p>这里集中定义 insert、stop、Spring AMQP consumer batch 的灰度开关和批量边界。
 * 自研 MQ start 内存聚合链路已经清理，历史配置中心里残留的 insertAggregate* 配置不会再被读取。
 * 默认值刻意保守：内部批量 API 可用、单次 SQL IN 限制为 500、批量失败允许拆回单条兜底。
 * 这样生产出现批量 SQL、分片路由或事务异常时，可以通过配置快速回到旧单条语义。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "alarm.batch")
public class AlarmBatchProperties {

    /** 是否启用 stop worker 批量 route 查询和按分片批量消警；关闭后回到旧单条 route 查询语义。 */
    private boolean stopEnabled = true;

    /** 是否启用内部 insert 批量持久化 API；Controller 和 MQ 单条入口仍保持原方法签名。 */
    private boolean insertEnabled = true;

    /** 单次 SQL IN 查询或批量 upsert 的最大分片大小，避免一次 IN 过长压垮数据库解析和执行计划。 */
    private int inLimit = 500;

    /** 批量 SQL 或批量事务失败时是否拆回单条兜底；生产灰度期建议保持 true。 */
    private boolean fallbackSingleOnBatchError = true;

    /** 是否启用 Spring AMQP consumer batch listener；开启后旧 RabbitMQAlarmListener 会被条件关闭。 */
    private boolean insertConsumerBatchEnabled = false;

    /** Rabbit 容器一次最多收集多少条消息后调用 batch listener。 */
    private int insertConsumerBatchSize = 100;

    /** Rabbit 容器为凑满 consumer batch 最多等待多久；过大增加低流量 ack 延迟，过小降低批量收益。 */
    private long insertConsumerBatchReceiveTimeoutMs = 50L;

    /** Rabbit batch listener 并发，格式支持 "min-max" 或单个数字；过大会放大 DB/Redis 压力。 */
    private String insertConsumerBatchConcurrency = "2-4";

    /** 每个 batch consumer 的 prefetch，至少应等于 batchSize，否则容器很难收满一批。 */
    private int insertConsumerBatchPrefetch = 100;

    /** consumer batch 普通成功日志开关；nack、reject、批量失败等风险日志始终保留。 */
    private boolean insertConsumerBatchLogEnabled = false;

    /** SQL IN 和批量 upsert 的统一兜底值，防止配置成 0 或负数后出现全量或空批行为。 */
    public int safeInLimit() {
        return inLimit <= 0 ? 500 : inLimit;
    }

    /** consumer batch 批大小兜底，防止容器被配置成 0 条批量。 */
    public int safeInsertConsumerBatchSize() {
        return insertConsumerBatchSize <= 0 ? 100 : insertConsumerBatchSize;
    }

    /** consumer batch receiveTimeout 兜底，保护低流量场景不会因为 0/负数配置产生忙等。 */
    public long safeInsertConsumerBatchReceiveTimeoutMs() {
        return insertConsumerBatchReceiveTimeoutMs <= 0 ? 50L : insertConsumerBatchReceiveTimeoutMs;
    }

    /** prefetch 必须至少等于 batchSize，否则 Rabbit 容器无法稳定形成真实批量。 */
    public int safeInsertConsumerBatchPrefetch() {
        return insertConsumerBatchPrefetch <= 0
                ? safeInsertConsumerBatchSize()
                : Math.max(insertConsumerBatchPrefetch, safeInsertConsumerBatchSize());
    }

    /** 并发配置解析兜底；格式错误时回到 2-4，避免生产因配置拼写导致 listener 启动失败。 */
    public int[] safeInsertConsumerBatchConcurrencyRange() {
        String value = insertConsumerBatchConcurrency == null ? "" : insertConsumerBatchConcurrency.trim();
        if (value.isEmpty()) {
            return new int[]{2, 4};
        }
        try {
            if (value.contains("-")) {
                String[] parts = value.split("-", 2);
                int min = Math.max(1, Integer.parseInt(parts[0].trim()));
                int max = Math.max(min, Integer.parseInt(parts[1].trim()));
                return new int[]{min, max};
            }
            int concurrency = Math.max(1, Integer.parseInt(value));
            return new int[]{concurrency, concurrency};
        } catch (Exception ignored) {
            return new int[]{2, 4};
        }
    }
}
