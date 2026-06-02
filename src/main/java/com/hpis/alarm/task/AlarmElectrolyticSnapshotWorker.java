package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmElectrolyticSnapshotWorkerProperties;
import com.hpis.alarm.service.AlarmElectrolyticSnapshotCommandService;
import com.hpis.alarm.service.support.ClaimedElectrolyticSnapshotBatch;
import com.hpis.alarm.service.support.SnapshotProjectionSupersededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 电解槽当前点位快照异步投影 worker。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmElectrolyticSnapshotWorker {

    private final AlarmElectrolyticSnapshotCommandService commandService;
    private final AlarmElectrolyticSnapshotWorkerProperties properties;
    private final ThreadPoolTaskExecutor executor;
    private final AlarmElectrolyticSnapshotWorkerSignal workerSignal;
    private final AtomicInteger inFlightBatches = new AtomicInteger(0);
    private final Object claimMonitor = new Object();

    public AlarmElectrolyticSnapshotWorker(AlarmElectrolyticSnapshotCommandService commandService,
                                           AlarmElectrolyticSnapshotWorkerProperties properties,
                                           AlarmElectrolyticSnapshotWorkerSignal workerSignal,
                                           @Qualifier("alarmElectrolyticSnapshotWorkerExecutor")
                                           ThreadPoolTaskExecutor executor) {
        this.commandService = commandService;
        this.properties = properties;
        this.workerSignal = workerSignal;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${alarm.ec-snapshot-worker.dispatchIntervalMs:100}")
    public void dispatch() {
        if (!properties.isDispatchEnabled() || !workerSignal.shouldRunCycle()) {
            return;
        }
        while (tryReserveInFlight()) {
            try {
                executor.execute(this::processClaimedBatch);
            } catch (RejectedExecutionException ex) {
                inFlightBatches.decrementAndGet();
                log.warn("电解槽快照 worker 专用线程池拒绝派发，inFlight={}", inFlightBatches.get());
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "${alarm.ec-snapshot-worker.recoveryIntervalMs:10000}")
    public void recoverExpiredClaims() {
        if (!properties.isDispatchEnabled() || inFlightBatches.get() > 0) {
            return;
        }
        int recovered = commandService.recoverExpiredClaims();
        if (recovered > 0) {
            log.warn("释放超时 PROCESSING 电解槽快照命令，recovered={}", recovered);
        }
    }

    private boolean tryReserveInFlight() {
        while (true) {
            int current = inFlightBatches.get();
            if (current >= properties.safeMaxInFlightBatches()) {
                return false;
            }
            if (inFlightBatches.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void processClaimedBatch() {
        ClaimedElectrolyticSnapshotBatch batch = null;
        try {
            batch = claimNextBatchWithRetry();
            if (batch.isEmpty()) {
                workerSignal.afterCycle(0);
                return;
            }
            int done = commandService.processClaimedBatch(batch.getLockToken(), batch.getCommands());
            workerSignal.afterCycle(done);
            if (properties.isLogEnabled()) {
                log.info("电解槽快照投影批次完成，claimed={}, done={}", batch.getCommands().size(), done);
            }
        } catch (SnapshotProjectionSupersededException ex) {
            if (batch != null && !batch.isEmpty()) {
                commandService.releaseSupersededClaim(batch.getLockToken());
            }
            workerSignal.wakeUp("projection-superseded");
            log.debug("snapshot projection superseded, claimed={}, reason={}",
                    batch == null ? 0 : batch.getCommands().size(), ex.getMessage());
        } catch (Exception ex) {
            if (batch != null && !batch.isEmpty()) {
                commandService.releaseClaim(batch.getLockToken(), ex);
            }
            log.error("电解槽快照投影批次失败，claimed={}, error={}",
                    batch == null ? 0 : batch.getCommands().size(), ex.getMessage(), ex);
        } finally {
            inFlightBatches.decrementAndGet();
        }
    }

    private ClaimedElectrolyticSnapshotBatch claimNextBatch() {
        /*
         * 单实例内串行认领短事务，避免多个投影线程同时 UPDATE ORDER BY LIMIT 扫描相邻主键范围。
         * 已认领批次的投影事务仍在线程池并行执行。
         */
        synchronized (claimMonitor) {
            return commandService.claimPendingBatch();
        }
    }

    private ClaimedElectrolyticSnapshotBatch claimNextBatchWithRetry() {
        int maxAttempts = properties.safeClaimRetryMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return claimNextBatch();
            } catch (DeadlockLoserDataAccessException ex) {
                if (attempt >= maxAttempts) {
                    throw ex;
                }
                log.warn("snapshot claim deadlock retry, attempt={}, maxAttempts={}", attempt, maxAttempts);
                sleepBeforeClaimRetry(attempt);
            }
        }
        throw new IllegalStateException("snapshot claim retry loop exhausted unexpectedly");
    }

    private void sleepBeforeClaimRetry(int attempt) {
        try {
            Thread.sleep(properties.safeClaimRetryBackoffMs() * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("snapshot claim retry interrupted", ex);
        }
    }
}
