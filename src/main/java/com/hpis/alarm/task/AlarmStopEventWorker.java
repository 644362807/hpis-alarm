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
    private final AlarmStopWorkerSignal workerSignal;

    public AlarmStopEventWorker(AlarmStopEventService stopEventService,
                                AlarmStopSideEffectService sideEffectService,
                                AlarmStopWorkerProperties properties,
                                AlarmStopWorkerSignal workerSignal) {
        this.stopEventService = stopEventService;
        this.sideEffectService = sideEffectService;
        this.properties = properties;
        this.workerSignal = workerSignal;
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.normalIntervalMs:1000}")
    public void processStopEvents() {
        /*
         * 空闲暂停只在进入数据库前短路。
         * 一旦 shouldRunCycle 返回 true，本轮仍按原可靠 worker 语义处理 PENDING、side effect 和 idle 状态回写。
         */
        if (!workerSignal.shouldRunCycle()) {
            return;
        }
        int total = 0;
        int sideEffectDone = 0;
        try {
            int loops = stopEventService.currentBatchLoops();
            for (int i = 0; i < loops; i++) {
                int applied = stopEventService.processPendingBatch();
                total += applied;
                if (applied == 0) {
                    break;
                }
            }
            if (properties.isIdlePauseEnabled()) {
                sideEffectDone = processSideEffectsOnce();
            }
            if (properties.isLogEnabled() && total > 0) {
                log.info("批量消警 worker 完成，本轮 applied={}, highTraffic={}", total, stopEventService.isHighTraffic());
            }
            if (properties.isLogEnabled() && sideEffectDone > 0) {
                log.info("消警副作用 worker 完成，本轮 done={}", sideEffectDone);
            }
            workerSignal.afterCycle(total, sideEffectDone);
        } catch (Exception ex) {
            workerSignal.wakeUp("process-failed", null);
            log.error("stop-worker process cycle failed, applied={}, sideEffectDone={}, error={}",
                    total, sideEffectDone, ex.getMessage(), ex);
        }
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.normalIntervalMs:1000}")
    public void processSideEffects() {
        /*
         * idlePauseEnabled=true 时，副作用跟随核心 stop worker 在同一轮里低频执行，
         * 避免单独 scheduled 在无任务时持续查 side effect 表。
         */
        if (properties.isIdlePauseEnabled()) {
            return;
        }
        int done = processSideEffectsOnce();
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

    private int processSideEffectsOnce() {
        if (!properties.isSideEffectEnabled()) {
            return 0;
        }
        return sideEffectService.processPendingBatch();
    }
}
