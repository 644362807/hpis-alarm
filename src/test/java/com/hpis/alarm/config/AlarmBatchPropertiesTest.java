package com.hpis.alarm.config;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AlarmBatchPropertiesTest {

    @Test
    public void consumerAndSqlBatchConfigurationUsesHardUpperBounds() {
        AlarmBatchProperties properties = new AlarmBatchProperties();
        properties.setInsertConsumerBatchSize(9999);
        properties.setInsertConsumerBatchPrefetch(9999);
        properties.setInsertConsumerBatchConcurrency("40-99");
        properties.setStopEventUpsertBatchSize(9999);
        properties.setElectrolyticSnapshotBatchSize(9999);

        assertEquals(500, properties.safeInsertConsumerBatchSize());
        assertEquals(2000, properties.safeInsertConsumerBatchPrefetch());
        assertArrayEquals(new int[]{32, 32}, properties.safeInsertConsumerBatchConcurrencyRange());
        assertEquals(500, properties.safeStopEventUpsertBatchSize());
        assertEquals(500, properties.safeElectrolyticSnapshotBatchSize());
        assertEquals(false, properties.isInsertItemProfileLogEnabled());
    }

    @Test
    public void electrolyticSnapshotModeFallsBackToSyncAndAllowsGrayModes() {
        AlarmBatchProperties properties = new AlarmBatchProperties();

        properties.setElectrolyticSnapshotMode(" async ");
        assertEquals("ASYNC", properties.safeElectrolyticSnapshotMode());

        properties.setElectrolyticSnapshotMode("dual_write");
        assertEquals("DUAL_WRITE", properties.safeElectrolyticSnapshotMode());

        properties.setElectrolyticSnapshotMode("invalid");
        assertEquals("SYNC", properties.safeElectrolyticSnapshotMode());
    }
}
