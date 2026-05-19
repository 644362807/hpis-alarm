package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmStopWorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates stop-worker wakeup and idle pause state.
 *
 * <p>The worker still uses Spring's scheduled entrypoint, but when it is idle
 * this signal lets the scheduled method return before touching the database.
 * A low-frequency probe is kept so pending rows are not stranded after restart,
 * missed wakeups, or multi-instance routing surprises.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmStopWorkerSignal {

    private final AlarmStopWorkerProperties properties;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicInteger emptyRounds = new AtomicInteger(0);
    private final AtomicLong lastProbeTime = new AtomicLong(0L);

    public AlarmStopWorkerSignal(AlarmStopWorkerProperties properties) {
        this.properties = properties;
    }

    public boolean shouldRunCycle() {
        if (!properties.isIdlePauseEnabled()) {
            return true;
        }
        if (active.get()) {
            return true;
        }
        long now = System.currentTimeMillis();
        long probeInterval = Math.max(1L, properties.getIdleProbeIntervalMs());
        long lastProbe = lastProbeTime.get();
        return now - lastProbe >= probeInterval && lastProbeTime.compareAndSet(lastProbe, now);
    }

    public void wakeUp(String reason, String alarmCid) {
        if (!properties.isIdlePauseEnabled()) {
            return;
        }
        emptyRounds.set(0);
        lastProbeTime.set(0L);
        if (active.compareAndSet(false, true)) {
            log.info("stop-worker awakened, reason={}, alarmCid={}", reason, alarmCid);
        }
    }

    public void afterCycle(int applied, int sideEffectDone) {
        if (!properties.isIdlePauseEnabled()) {
            return;
        }
        if (applied > 0 || sideEffectDone > 0) {
            emptyRounds.set(0);
            if (active.compareAndSet(false, true)) {
                log.info("stop-worker idle probe found work, applied={}, sideEffectDone={}", applied, sideEffectDone);
            }
            return;
        }
        if (!active.get()) {
            return;
        }
        int rounds = emptyRounds.incrementAndGet();
        int threshold = Math.max(1, properties.getIdleConfirmCount());
        if (rounds >= threshold && active.compareAndSet(true, false)) {
            lastProbeTime.set(System.currentTimeMillis());
            log.info("stop-worker entered idle pause after {} empty rounds", rounds);
        }
    }
}
