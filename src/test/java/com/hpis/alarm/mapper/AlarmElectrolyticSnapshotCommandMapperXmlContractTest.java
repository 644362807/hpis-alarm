package com.hpis.alarm.mapper;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

public class AlarmElectrolyticSnapshotCommandMapperXmlContractTest {

    @Test
    public void snapshotCommandUsesPointTokenAndVersionGuards() throws Exception {
        String xml = readMapperXml();

        assertTrue(xml.contains("id=\"upsertActiveBatch\""));
        assertTrue(xml.contains("id=\"enqueueDeleteByAlarmId\""));
        assertTrue(xml.contains("id=\"releaseSupersededByToken\""));
        assertTrue(xml.contains("where alarm_id = #{alarmId}"));
        assertTrue(xml.contains("id=\"claimPendingBatch\""));
        assertTrue(xml.contains("order by point_hash"));
        assertTrue(xml.contains("id=\"markDoneBatch\""));
        assertTrue(xml.contains("point_hash = #{command.pointHash} and version = #{command.version}"));
        assertTrue(xml.contains("and lock_token = #{lockToken}"));
        assertTrue(xml.contains("id=\"releaseExpiredProcessing\""));
    }

    private String readMapperXml() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/mapper/alarm/AlarmElectrolyticSnapshotCommandMapper.xml");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("missing AlarmElectrolyticSnapshotCommandMapper.xml");
            }
            byte[] buffer = new byte[1024];
            int length;
            while ((length = input.read(buffer)) >= 0) {
                output.write(buffer, 0, length);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
