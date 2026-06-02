package com.hpis.alarm.config;

import com.hpis.alarm.service.support.AlarmBatchChunker;
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

    /**
     * 批量失败后允许拆回单条 SQL 的最大数量。
     *
     * <p>单条降级只用于隔离小批坏数据，不能在数据库异常时把大批量重新放大为 N 次 SQL。
     * 小于等于 0 时按 100，超过 500 时钳制为 500；需要停止拆单时关闭 fallbackSingleOnBatchError。</p>
     */
    private int fallbackSingleMaxItems = 100;

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

    /**
     * start 单条阶段成功日志开关。
     *
     * <p>默认关闭，避免高流量时每条报警打印 START、RESOLVE_DEVICE、BUILD_ALARM、PERSIST
     * 放大日志 IO。批次级 Profiling 和异常日志不受影响；定位单条链路时再短时灰度开启。</p>
     */
    private boolean insertItemProfileLogEnabled = false;

    /** 是否启用 MQ stop event 批量可靠入队；关闭后恢复逐条 upsert，便于生产灰度回退。 */
    private boolean stopEventBatchEnabled = true;

    /** stop event 单次 multi-values upsert 大小；默认 100，硬上限 500。 */
    private int stopEventUpsertBatchSize = 100;

    /** 电解槽当前点位快照单次 upsert 大小；缩短并发事务锁窗口，默认 100，硬上限 500。 */
    private int electrolyticSnapshotBatchSize = 100;

    /**
     * 电解槽当前点位快照模式：SYNC 保持旧同步写入；DUAL_WRITE 同步写入并记录可靠命令；
     * ASYNC 只记录可靠命令，由独立 worker 最终一致投影。
     */
    private String electrolyticSnapshotMode = "SYNC";

    /** start 批量事务遇到 deadlock/锁冲突后的最大重试次数；只重试瞬态锁错误。 */
    private int startLockRetryMaxAttempts = 3;

    /** SQL IN 和批量 upsert 的统一兜底值，防止配置成 0 或负数后出现全量或空批行为。 */
    public int safeInLimit() {
        return AlarmBatchChunker.safeBatchSize(inLimit);
    }

    /** 单条兜底数量硬边界，防止数据库故障时出现批量转 N+1 的二次放大。 */
    public int safeFallbackSingleMaxItems() {
        return AlarmBatchChunker.safeBatchSize(fallbackSingleMaxItems <= 0 ? 100 : fallbackSingleMaxItems);
    }

    /** consumer batch 批大小兜底，防止容器被配置成 0 条批量。 */
    public int safeInsertConsumerBatchSize() {
        return AlarmBatchChunker.safeBatchSize(insertConsumerBatchSize <= 0 ? 100 : insertConsumerBatchSize);
    }

    /** consumer batch receiveTimeout 兜底，保护低流量场景不会因为 0/负数配置产生忙等。 */
    public long safeInsertConsumerBatchReceiveTimeoutMs() {
        return insertConsumerBatchReceiveTimeoutMs <= 0 ? 50L : insertConsumerBatchReceiveTimeoutMs;
    }

    /** prefetch 必须至少等于 batchSize，否则 Rabbit 容器无法稳定形成真实批量。 */
    public int safeInsertConsumerBatchPrefetch() {
        int configured = insertConsumerBatchPrefetch <= 0
                ? safeInsertConsumerBatchSize()
                : Math.max(insertConsumerBatchPrefetch, safeInsertConsumerBatchSize());
        return Math.min(configured, 2000);
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
                int min = Math.min(32, Math.max(1, Integer.parseInt(parts[0].trim())));
                int max = Math.min(32, Math.max(min, Integer.parseInt(parts[1].trim())));
                return new int[]{min, max};
            }
            int concurrency = Math.min(32, Math.max(1, Integer.parseInt(value)));
            return new int[]{concurrency, concurrency};
        } catch (Exception ignored) {
            return new int[]{2, 4};
        }
    }

    /** stop event 可靠入队 SQL 的硬边界，防止高峰期生成超长 multi-values INSERT。 */
    public int safeStopEventUpsertBatchSize() {
        return AlarmBatchChunker.safeBatchSize(stopEventUpsertBatchSize <= 0 ? 100 : stopEventUpsertBatchSize);
    }

    /** 电解槽当前点位快照 upsert 的硬边界，避免并发写入时持有过大的锁集合。 */
    public int safeElectrolyticSnapshotBatchSize() {
        return AlarmBatchChunker.safeBatchSize(electrolyticSnapshotBatchSize <= 0 ? 100 : electrolyticSnapshotBatchSize);
    }

    public String safeElectrolyticSnapshotMode() {
        String mode = electrolyticSnapshotMode == null ? "" : electrolyticSnapshotMode.trim().toUpperCase();
        if ("DUAL_WRITE".equals(mode) || "ASYNC".equals(mode)) {
            return mode;
        }
        return "SYNC";
    }

    /** 锁冲突重试仅用于短暂竞争，最多 3 次，避免数据库异常时长时间占用 Rabbit consumer。 */
    public int safeStartLockRetryMaxAttempts() {
        return Math.max(0, Math.min(startLockRetryMaxAttempts, 3));
    }
}
