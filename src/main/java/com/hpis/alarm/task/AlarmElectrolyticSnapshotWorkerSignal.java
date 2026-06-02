package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmElectrolyticSnapshotWorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 电解槽快照 worker 的空闲暂停和可靠低频探针。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmElectrolyticSnapshotWorkerSignal {

    private final AlarmElectrolyticSnapshotWorkerProperties properties;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger emptyRounds = new AtomicInteger(0);
    private final AtomicLong lastProbeTime = new AtomicLong(0L);
    private final AtomicLong wakeUpAt = new AtomicLong(0L);

    public AlarmElectrolyticSnapshotWorkerSignal(AlarmElectrolyticSnapshotWorkerProperties properties) {
        this.properties = properties;
    }

    public boolean shouldRunCycle() {
        if (active.get()) {
            return System.currentTimeMillis() >= wakeUpAt.get();
        }
        long now = System.currentTimeMillis();
        long probeInterval = Math.max(1000L, properties.getIdleProbeIntervalMs());
        long lastProbe = lastProbeTime.get();
        return now - lastProbe >= probeInterval && lastProbeTime.compareAndSet(lastProbe, now);
    }

    public void wakeUp(String reason) {
        emptyRounds.set(0);
        lastProbeTime.set(0L);
        wakeUpAt.set(0L);
        if (active.compareAndSet(false, true)) {
            log.info("电解槽快照 worker awakened, reason={}", reason);
        }
    }

    public void wakeUpAfter(String reason, long delayMs) {
        emptyRounds.set(0);
        lastProbeTime.set(0L);
        long targetTime = System.currentTimeMillis() + Math.max(0L, delayMs);
        if (active.compareAndSet(false, true)) {
            wakeUpAt.set(targetTime);
            log.info("snapshot worker awakened after delay, reason={}, delayMs={}", reason, delayMs);
            return;
        }
        while (true) {
            long current = wakeUpAt.get();
            if (current <= System.currentTimeMillis() || current <= targetTime
                    || wakeUpAt.compareAndSet(current, targetTime)) {
                return;
            }
        }
    }

    public void afterCycle(int done) {
        if (done > 0) {
            emptyRounds.set(0);
            wakeUpAt.set(0L);
            active.set(true);
            return;
        }
        if (!active.get()) {
            return;
        }
        int rounds = emptyRounds.incrementAndGet();
        if (rounds >= Math.max(1, properties.getIdleConfirmCount()) && active.compareAndSet(true, false)) {
            lastProbeTime.set(System.currentTimeMillis());
            log.info("电解槽快照 worker entered idle pause after {} empty rounds", rounds);
        }
    }
}
