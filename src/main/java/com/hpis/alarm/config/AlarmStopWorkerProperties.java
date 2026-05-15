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

    /**
     * 是否打印定时 worker 的普通完成日志。
     *
     * <p>生产高流量时 worker 会频繁调度，默认打印每轮日志会放大 IO 压力。
     * 因此默认关闭，只保留异常日志；需要观察处理节奏时可在 Nacos 临时打开。</p>
     */
    private boolean logEnabled = false;
}
