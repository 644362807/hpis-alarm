package com.hpis.alarm.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AlarmElectrolyticSnapshotWorkerPropertiesTest {

    @Test
    public void snapshotWorkerUsesConservativeHardBounds() {
        AlarmElectrolyticSnapshotWorkerProperties properties = new AlarmElectrolyticSnapshotWorkerProperties();
        properties.setWorkerThreads(99);
        properties.setClaimBatchSize(999);
        properties.setMaxInFlightBatches(99);
        properties.setRecoveryBatchSize(999);
        properties.setClaimRetryMaxAttempts(99);
        properties.setClaimRetryBackoffMs(1L);
        properties.setInitialAvailableDelayMs(99999L);

        assertEquals(16, properties.safeWorkerThreads());
        assertEquals(100, properties.safeClaimBatchSize());
        assertEquals(16, properties.safeMaxInFlightBatches());
        assertEquals(500, properties.safeRecoveryBatchSize());
        assertEquals(5, properties.safeClaimRetryMaxAttempts());
        assertEquals(10L, properties.safeClaimRetryBackoffMs());
        assertEquals(5000L, properties.safeInitialAvailableDelayMs());
    }
}
