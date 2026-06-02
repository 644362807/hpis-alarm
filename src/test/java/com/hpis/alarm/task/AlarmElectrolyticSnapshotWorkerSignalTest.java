package com.hpis.alarm.task;

import com.hpis.alarm.config.AlarmElectrolyticSnapshotWorkerProperties;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AlarmElectrolyticSnapshotWorkerSignalTest {

    @Test
    public void idleWorkerStopsHotPollingAndCommandWakesIt() {
        AlarmElectrolyticSnapshotWorkerProperties properties = new AlarmElectrolyticSnapshotWorkerProperties();
        properties.setIdleConfirmCount(2);
        properties.setIdleProbeIntervalMs(60000L);
        AlarmElectrolyticSnapshotWorkerSignal signal = new AlarmElectrolyticSnapshotWorkerSignal(properties);

        assertTrue(signal.shouldRunCycle());
        signal.afterCycle(0);
        signal.afterCycle(0);
        assertFalse(signal.shouldRunCycle());

        signal.wakeUp("unit-test");
        assertTrue(signal.shouldRunCycle());
    }

    @Test
    public void delayedWakeDoesNotDispatchBeforeProjectionWindow() throws Exception {
        AlarmElectrolyticSnapshotWorkerProperties properties = new AlarmElectrolyticSnapshotWorkerProperties();
        properties.setIdleConfirmCount(1);
        properties.setIdleProbeIntervalMs(60000L);
        AlarmElectrolyticSnapshotWorkerSignal signal = new AlarmElectrolyticSnapshotWorkerSignal(properties);
        signal.afterCycle(0);

        signal.wakeUpAfter("unit-test-delay", 30L);

        assertFalse(signal.shouldRunCycle());
        Thread.sleep(40L);
        assertTrue(signal.shouldRunCycle());
    }
}
