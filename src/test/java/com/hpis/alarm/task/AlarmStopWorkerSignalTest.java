package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmStopWorkerProperties;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class AlarmStopWorkerSignalTest {

    @Test
    public void entersIdleAfterConfiguredEmptyCyclesAndSkipsDbPolling() {
        AlarmStopWorkerProperties properties = new AlarmStopWorkerProperties();
        properties.setIdlePauseEnabled(true);
        properties.setIdleConfirmCount(3);
        properties.setIdleProbeIntervalMs(60000L);
        AlarmStopWorkerSignal signal = new AlarmStopWorkerSignal(properties);

        assertThat(signal.shouldRunCycle()).isTrue();
        signal.afterCycle(0, 0);
        assertThat(signal.shouldRunCycle()).isTrue();
        signal.afterCycle(0, 0);
        assertThat(signal.shouldRunCycle()).isTrue();
        signal.afterCycle(0, 0);

        assertThat(signal.shouldRunCycle()).isFalse();
    }

    @Test
    public void recordStopWakeupReactivatesIdleWorker() {
        AlarmStopWorkerProperties properties = new AlarmStopWorkerProperties();
        properties.setIdlePauseEnabled(true);
        properties.setIdleConfirmCount(1);
        properties.setIdleProbeIntervalMs(60000L);
        AlarmStopWorkerSignal signal = new AlarmStopWorkerSignal(properties);

        signal.afterCycle(0, 0);
        assertThat(signal.shouldRunCycle()).isFalse();

        signal.wakeUp("recordStop", "cid-1");

        assertThat(signal.shouldRunCycle()).isTrue();
    }

    @Test
    public void idleProbeAllowsLowFrequencyDbCheck() throws Exception {
        AlarmStopWorkerProperties properties = new AlarmStopWorkerProperties();
        properties.setIdlePauseEnabled(true);
        properties.setIdleConfirmCount(1);
        properties.setIdleProbeIntervalMs(1L);
        AlarmStopWorkerSignal signal = new AlarmStopWorkerSignal(properties);

        signal.afterCycle(0, 0);
        Thread.sleep(5L);

        assertThat(signal.shouldRunCycle()).isTrue();
    }
}
