package com.hpis.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 消警可靠处理 worker 配置。
 *
 * <p>这组配置只影响“结束报警”链路：MQ stop 消息先落 alarm_stop_event，再由后台 worker
 * 批量关闭业务分片表和 hot/stale cid 路由。这样 MQ ack 不再依赖线程池是否还有空队列，
 * 高峰期也可以先保护核心消警，再延后执行副作用同步和历史清理。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "alarm.stop-worker")
public class AlarmStopWorkerProperties {

    /** 是否启用 PROCESSING claim 派发器。关闭后只暂停新认领，不影响 MQ stop 落库。 */
    private boolean dispatchEnabled = true;

    /** PROCESSING claim 派发扫描间隔。scheduled 只负责投递小任务，不在调度线程中跑长事务。 */
    private long dispatchIntervalMs = 100L;

    /** 专用 stop executor 工作线程数。不要与 push、WebSocket 或通用 @Async 线程池复用。 */
    private int workerThreads = 4;

    /** 单个 PROCESSING claim 最大事件数。硬上限 500，生产初始值 200。 */
    private int claimBatchSize = 200;

    /** 同一实例最多并行执行多少个已认领批次。 */
    private int maxInFlightBatches = 4;

    /** PROCESSING 超过该时间仍未完成时允许回收。 */
    private long processingTimeoutMs = 60000L;

    /** 超时 PROCESSING 回收任务每轮最大处理量。 */
    private int claimRecoveryBatchSize = 500;

    /** 超时 PROCESSING 回收任务扫描间隔。 */
    private long claimRecoveryIntervalMs = 10000L;

    /** 核心事务异常释放 PROCESSING 后的延迟重试时间。 */
    private long processingRetryDelayMs = 1000L;

    /** claim 短事务遇到 deadlock 等瞬时数据库冲突时的最大尝试次数。 */
    private int claimRetryMaxAttempts = 3;

    /** claim 短事务瞬时数据库冲突后的基础退避时间。 */
    private long claimRetryBackoffMs = 25L;

    /** route 暂不可见时的延迟重试时间。 */
    private long routeMissingRetryDelayMs = 5000L;

    /** ROUTE_MISSING 聚合日志间隔。避免高流量场景逐批 WARN 放大日志 IO。 */
    private int routeMissingProfileLogEveryBatches = 100;

    /**
     * MQ stop 首次落库后允许 worker claim 前的短暂聚批窗口。
     *
     * <p>默认 0 保持旧语义；生产灰度可从 100-300ms 选择。该延迟同时给 start route 提交留出可见窗口，
     * 避免交替上报时 worker 长期只认领 1-13 条并产生 ROUTE_MISSING 重试。</p>
     */
    private long initialAvailableDelayMs = 0L;

    /** PENDING 数低于该值时视为低流量，可以执行清理和副作用。 */
    private int lowWatermark = 500;

    /** PENDING 数高于该值时进入高流量模式，优先清空核心消警事件。 */
    private int highWatermark = 5000;

    /** 低/正常流量每批处理数量。 */
    private int normalBatchSize = 500;

    /** 高流量每批处理数量。 */
    private int highBatchSize = 2000;

    /** stop worker 常规扫描间隔。 */
    private long normalIntervalMs = 1000L;

    /** 高流量目标扫描间隔，本阶段通过 highBatchSize + maxParallelism 提升单轮处理能力。 */
    private long highIntervalMs = 100L;

    /** 高流量模式下单轮最多连续处理批次数，避免一次调度占用过久。 */
    private int maxParallelism = 4;

    /** 是否生成并执行结束后的设备同步、扩展表清理等副作用事件。 */
    private boolean sideEffectEnabled = true;

    /** APPLIED stop event 保留分钟数，到期后低流量窗口物理删除。 */
    private int appliedRetentionMinutes = 30;

    /** stop event 清理批大小。 */
    private int cleanupBatchSize = 1000;

    /** stop event 清理扫描间隔。 */
    private long cleanupIntervalMs = 60000L;

    /** FAILED 事件保留天数，本阶段只记录配置，不自动删除 FAILED。 */
    private int failedRetentionDays = 7;

    /** 为 true 时只有低流量模式才清理 APPLIED 事件。 */
    private boolean cleanupOnlyLowTraffic = true;

    /** 单个 stop event 连续异常达到该次数后转 FAILED，避免永久热重试同一坏数据。 */
    private int maxRetry = 5;

    /** FAILED/ROUTE_MISSING 恢复扫描批大小，用于修复 route 后到造成的误失败。 */
    private int routeMissingRecoveryBatchSize = 500;

    /** ROUTE_MISSING 转 FAILED 后至少等待多久再尝试恢复扫描。 */
    private long routeMissingRecoveryDelayMs = 1000L;

    /** FAILED/ROUTE_MISSING 低频恢复扫描间隔。 */
    private long routeMissingRecoveryIntervalMs = 10000L;

    /**
     * 是否在连续空轮次后暂停 stop worker 的数据库轮询。
     *
     * <p>开启后 @Scheduled 入口仍会触发，但空闲状态只做内存判断并直接返回；
     * 收到新的 stop 消息或低频兜底扫描命中后再恢复查库处理。</p>
     */
    private boolean idlePauseEnabled = true;

    /** 连续空轮次数达到该值后进入 idle pause。 */
    private int idleConfirmCount = 3;

    /** idle pause 后的低频兜底查库间隔，单位毫秒。 */
    private long idleProbeIntervalMs = 60000L;

    /**
     * 是否打印定时 worker 的普通完成日志。
     *
     * <p>生产高流量时 worker 会频繁调度，默认打印每轮日志会放大 IO 压力。
     * 因此默认关闭，只保留异常日志；需要观察处理节奏时可在 Nacos 临时打开。</p>
     */
    private boolean logEnabled = false;

    public int safeWorkerThreads() {
        return Math.max(1, Math.min(workerThreads, 32));
    }

    public int safeClaimBatchSize() {
        return Math.max(1, Math.min(claimBatchSize, 500));
    }

    public int safeMaxInFlightBatches() {
        return Math.max(1, Math.min(maxInFlightBatches, 32));
    }

    public int safeClaimRecoveryBatchSize() {
        return Math.max(1, Math.min(claimRecoveryBatchSize, 500));
    }

    public long safeProcessingTimeoutMs() {
        return Math.max(1000L, processingTimeoutMs);
    }

    public long safeProcessingRetryDelayMs() {
        return Math.max(100L, processingRetryDelayMs);
    }

    public int safeClaimRetryMaxAttempts() {
        return Math.max(1, Math.min(claimRetryMaxAttempts, 5));
    }

    public long safeClaimRetryBackoffMs() {
        return Math.max(1L, Math.min(claimRetryBackoffMs, 1000L));
    }

    public long safeRouteMissingRetryDelayMs() {
        return Math.max(100L, routeMissingRetryDelayMs);
    }

    public int safeRouteMissingProfileLogEveryBatches() {
        return Math.max(1, Math.min(routeMissingProfileLogEveryBatches, 10000));
    }

    public long safeInitialAvailableDelayMs() {
        return Math.max(0L, Math.min(initialAvailableDelayMs, 5000L));
    }

    /**
     * PROCESSING 失败重试必须有硬上限，避免配置错误让毒任务永久占用 stop worker。
     * 上限只控制异常释放和超时回收；ROUTE_MISSING 仍沿用同一 maxRetry 语义。
     */
    public int safeMaxRetry() {
        return Math.max(1, Math.min(maxRetry, 100));
    }
}
