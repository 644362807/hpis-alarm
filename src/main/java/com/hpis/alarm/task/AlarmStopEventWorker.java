package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.service.AlarmStopEventClaimService;
import com.hpis.alarm.service.AlarmStopEventService;
import com.hpis.alarm.service.AlarmStopSideEffectService;
import com.hpis.alarm.service.support.ClaimedStopBatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 消警后台 worker。
 *
 * <p>scheduled 入口只负责向专用线程池派发小批任务。每个任务先用短事务把 PENDING 原子
 * 认领为 PROCESSING，再用独立事务关闭业务分片、cid route 和 stop event。这样大积压下
 * 调度入口不会被单次长循环占住，也不会让多个线程重复处理同一批数据。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmStopEventWorker {

    private final AlarmStopEventService stopEventService;
    private final AlarmStopEventClaimService claimService;
    private final AlarmStopSideEffectService sideEffectService;
    private final AlarmStopWorkerProperties properties;
    private final AlarmStopWorkerSignal workerSignal;
    private final ThreadPoolTaskExecutor stopExecutor;
    private final AtomicInteger inFlightBatches = new AtomicInteger(0);
    private final Object claimMonitor = new Object();

    public AlarmStopEventWorker(AlarmStopEventService stopEventService,
                                AlarmStopEventClaimService claimService,
                                AlarmStopSideEffectService sideEffectService,
                                AlarmStopWorkerProperties properties,
                                AlarmStopWorkerSignal workerSignal,
                                @Qualifier("alarmStopWorkerExecutor") ThreadPoolTaskExecutor stopExecutor) {
        this.stopEventService = stopEventService;
        this.claimService = claimService;
        this.sideEffectService = sideEffectService;
        this.properties = properties;
        this.workerSignal = workerSignal;
        this.stopExecutor = stopExecutor;
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.dispatchIntervalMs:100}")
    public void processStopEvents() {
        if (!properties.isDispatchEnabled() || !workerSignal.shouldRunCycle()) {
            return;
        }
        int maxInFlight = properties.safeMaxInFlightBatches();
        while (tryReserveInFlight(maxInFlight)) {
            try {
                stopExecutor.execute(this::processClaimedBatch);
            } catch (RejectedExecutionException ex) {
                inFlightBatches.decrementAndGet();
                log.warn("stop-worker 专用线程池拒绝派发，inFlight={}, maxInFlight={}",
                        inFlightBatches.get(), maxInFlight);
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.normalIntervalMs:1000}")
    public void processSideEffects() {
        if (claimService.hasOutstandingEvents()) {
            return;
        }
        int done = processSideEffectsOnce();
        if (properties.isLogEnabled() && done > 0) {
            log.info("消警副作用 worker 完成，本轮 done={}", done);
        }
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.claimRecoveryIntervalMs:10000}")
    public void recoverExpiredClaims() {
        if (hasInFlightBatches()) {
            return;
        }
        int recovered = claimService.recoverExpiredClaims();
        if (recovered > 0) {
            workerSignal.wakeUp("recover-expired-processing", null);
            log.warn("释放超时 PROCESSING stop event，recovered={}", recovered);
        }
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.routeMissingRecoveryIntervalMs:10000}")
    public void recoverFailedRouteMissingEvents() {
        if (hasInFlightBatches()) {
            return;
        }
        int recovered = stopEventService.recoverFailedRouteMissingEvents();
        if (recovered > 0) {
            workerSignal.wakeUp("recover-failed-route-missing", null);
            log.warn("恢复历史 FAILED/ROUTE_MISSING stop event，recovered={}", recovered);
        }
    }

    @Scheduled(fixedDelayString = "${alarm.stop-worker.cleanupIntervalMs:60000}")
    public void cleanupAppliedEvents() {
        if (hasInFlightBatches()) {
            return;
        }
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

    private boolean tryReserveInFlight(int maxInFlight) {
        while (true) {
            int current = inFlightBatches.get();
            if (current >= maxInFlight) {
                return false;
            }
            if (inFlightBatches.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void processClaimedBatch() {
        ClaimedStopBatch batch = null;
        try {
            batch = claimNextBatch();
            if (batch.isEmpty()) {
                workerSignal.afterCycle(claimService.hasOutstandingEvents() ? 1 : 0, 0);
                return;
            }
            int applied = stopEventService.processClaimedBatch(batch.getLockToken(), batch.getEvents());
            workerSignal.afterCycle(batch.getEvents().size(), 0);
            if (properties.isLogEnabled()) {
                log.info("stop-worker PROCESSING 批次完成，claimed={}, applied={}, inFlight={}",
                        batch.getEvents().size(), applied, inFlightBatches.get());
            }
        } catch (Exception ex) {
            if (batch != null && !batch.isEmpty()) {
                claimService.releaseClaim(batch.getLockToken(), ex);
            }
            workerSignal.wakeUp("process-failed", null);
            log.error("stop-worker PROCESSING 批次失败，claimed={}, error={}",
                    batch == null ? 0 : batch.getEvents().size(), ex.getMessage(), ex);
        } finally {
            inFlightBatches.decrementAndGet();
        }
    }

    private ClaimedStopBatch claimNextBatch() {
        /*
         * UPDATE ... ORDER BY ... LIMIT claim 在 MySQL 下会扫描相邻索引范围。单实例内串行认领，
         * 只保护短事务；已经认领的业务关闭事务仍在线程池并行执行。
         */
        synchronized (claimMonitor) {
            int maxAttempts = properties.safeClaimRetryMaxAttempts();
            for (int attempt = 1; ; attempt++) {
                try {
                    return claimService.claimPendingBatch();
                } catch (TransientDataAccessException ex) {
                    if (attempt >= maxAttempts) {
                        throw ex;
                    }
                    sleepBeforeClaimRetry(attempt);
                }
            }
        }
    }

    private boolean hasInFlightBatches() {
        return inFlightBatches.get() > 0;
    }

    private void sleepBeforeClaimRetry(int attempt) {
        try {
            Thread.sleep(properties.safeClaimRetryBackoffMs() * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("stop-worker claim retry interrupted", ex);
        }
    }
}
