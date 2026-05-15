package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.service.AlarmStopEventService;
import com.hpis.alarm.service.AlarmStopSideEffectService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消警后台 worker。
 *
 * <p>核心原则是高流量时只抢救 PENDING stop event：加大批量、连续处理多批，并暂停清理和
 * 副作用执行；低流量时恢复副作用和 APPLIED 物理清理。这样 1w stop 堆积不会继续堵住
 * 新报警的线程池，也不会因为远程同步慢导致 alarm_endTime 长时间缺失。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmStopEventWorker {

    private final AlarmStopEventService stopEventService;
    private final AlarmStopSideEffectService sideEffectService;
    private final AlarmStopWorkerProperties properties;

    public AlarmStopEventWorker(AlarmStopEventService stopEventService,
                                AlarmStopSideEffectService sideEffectService,
                                AlarmStopWorkerProperties properties) {
        this.stopEventService = stopEventService;
        this.sideEffectService = sideEffectService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.normalIntervalMs:1000}")
    public void processStopEvents() {
        int loops = stopEventService.currentBatchLoops();
        int total = 0;
        for (int i = 0; i < loops; i++) {
            int applied = stopEventService.processPendingBatch();
            total += applied;
            if (applied == 0) {
                break;
            }
        }
        if (properties.isLogEnabled() && total > 0) {
            log.info("批量消警 worker 完成，本轮 applied={}, highTraffic={}", total, stopEventService.isHighTraffic());
        }
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.normalIntervalMs:1000}")
    public void processSideEffects() {
        if (!properties.isSideEffectEnabled()) {
            return;
        }
        int done = sideEffectService.processPendingBatch();
        if (properties.isLogEnabled() && done > 0) {
            log.info("消警副作用 worker 完成，本轮 done={}", done);
        }
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.cleanupIntervalMs:60000}")
    public void cleanupAppliedEvents() {
        int deleted = stopEventService.cleanupAppliedIfAllowed();
        if (properties.isLogEnabled() && deleted > 0) {
            log.info("低流量清理 alarm_stop_event APPLIED 记录完成，deleted={}", deleted);
        }
    }
}
