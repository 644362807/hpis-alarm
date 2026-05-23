package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmStopWorkerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * stop worker 空闲暂停与唤醒信号。
 *
 * <p>worker 仍然保留 Spring {@code @Scheduled} 入口，但空闲后先走内存状态判断，
 * 直接返回，不再每秒访问 alarm_stop_event。这样可以降低无任务时的 DB 和日志噪音。</p>
 *
 * <p>可靠性边界：不能改成纯内存唤醒。服务重启、漏唤醒或多实例部署时，仍必须保留低频 probe，
 * 否则 PENDING stop event 可能永久卡住。</p>
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

    /**
     * 判断本轮 scheduled 是否需要真正扫库。
     *
     * <p>active=true 时正常执行；进入 idle 后只允许按 idleProbeIntervalMs 做低频兜底扫描。
     * 返回 false 时 scheduled 方法应立即返回，不能触碰数据库。</p>
     */
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
        /*
         * recordStop 成功落库后调用 wakeUp。
         * 重复 wake 是幂等的：只会把 active 置为 true，不会创建额外任务，也不会重复消费 stop event。
         */
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
        /*
         * 连续空转达到 idleConfirmCount 后才进入 idle，避免偶发空批导致 worker 过早暂停。
         * 如果低频 probe 命中任务，会重新激活，让下一轮恢复正常扫描。
         */
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
