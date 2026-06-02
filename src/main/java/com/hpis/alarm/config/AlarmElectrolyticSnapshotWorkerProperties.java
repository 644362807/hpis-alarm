package com.hpis.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 电解槽当前点位快照异步投影 worker 配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "alarm.ec-snapshot-worker")
public class AlarmElectrolyticSnapshotWorkerProperties {

    /** 是否派发异步投影任务。灰度初始关闭，ASYNC 模式开启前必须先启动。 */
    private boolean dispatchEnabled = false;

    /** 派发扫描间隔。 */
    private long dispatchIntervalMs = 100L;

    /** 独立线程池大小，不复用 MQ consumer 或 stop worker。 */
    private int workerThreads = 2;

    /** 每次认领点位数。 */
    private int claimBatchSize = 100;

    /** 单实例最大在途批次数。 */
    private int maxInFlightBatches = 2;

    /** PROCESSING 超时回收阈值。 */
    private long processingTimeoutMs = 60000L;

    /** Snapshot claim short-transaction deadlock retry attempts. */
    private int claimRetryMaxAttempts = 3;

    /** Snapshot claim short-transaction deadlock retry backoff. */
    private long claimRetryBackoffMs = 50L;

    /** 失败后的延迟重试时间。 */
    private long retryDelayMs = 5000L;

    /** Delay the first claim so projection does not contend with the core transaction for the same point hash. */
    private long initialAvailableDelayMs = 2000L;

    /** 单命令最大重试次数。 */
    private int maxRetry = 5;

    /** 超时回收每批上限。 */
    private int recoveryBatchSize = 500;

    /** 超时回收扫描间隔。 */
    private long recoveryIntervalMs = 10000L;

    /** 普通成功日志开关。 */
    private boolean logEnabled = false;

    /** 连续空批达到该次数后进入内存 idle pause。 */
    private int idleConfirmCount = 3;

    /** idle pause 后低频兜底探针间隔。 */
    private long idleProbeIntervalMs = 1000L;

    public int safeWorkerThreads() {
        return Math.max(1, Math.min(workerThreads, 16));
    }

    public int safeClaimBatchSize() {
        return Math.max(1, Math.min(claimBatchSize, 100));
    }

    public int safeMaxInFlightBatches() {
        return Math.max(1, Math.min(maxInFlightBatches, 16));
    }

    public long safeProcessingTimeoutMs() {
        return Math.max(1000L, processingTimeoutMs);
    }

    public int safeClaimRetryMaxAttempts() {
        return Math.max(1, Math.min(claimRetryMaxAttempts, 5));
    }

    public long safeClaimRetryBackoffMs() {
        return Math.max(10L, Math.min(claimRetryBackoffMs, 1000L));
    }

    public long safeRetryDelayMs() {
        return Math.max(100L, retryDelayMs);
    }

    public long safeInitialAvailableDelayMs() {
        return Math.max(0L, Math.min(initialAvailableDelayMs, 5000L));
    }

    public int safeMaxRetry() {
        return Math.max(1, Math.min(maxRetry, 20));
    }

    public int safeRecoveryBatchSize() {
        return Math.max(1, Math.min(recoveryBatchSize, 500));
    }
}
