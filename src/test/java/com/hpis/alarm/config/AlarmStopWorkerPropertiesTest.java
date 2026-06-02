package com.hpis.alarm.config;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AlarmStopWorkerPropertiesTest {

    @Test
    public void routeMissingProfileLogIntervalIsClamped() {
        AlarmStopWorkerProperties properties = new AlarmStopWorkerProperties();

        properties.setRouteMissingProfileLogEveryBatches(0);
        assertEquals(1, properties.safeRouteMissingProfileLogEveryBatches());

        properties.setRouteMissingProfileLogEveryBatches(99999);
        assertEquals(10000, properties.safeRouteMissingProfileLogEveryBatches());
    }

    @Test
    public void maxRetryIsClampedForProcessingRelease() {
        AlarmStopWorkerProperties properties = new AlarmStopWorkerProperties();

        properties.setMaxRetry(0);
        assertEquals(1, properties.safeMaxRetry());

        properties.setMaxRetry(999);
        assertEquals(100, properties.safeMaxRetry());
    }
}
