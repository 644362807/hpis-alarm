package com.hpis.alarm.mapper;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

public class AlarmStopEventMapperXmlContractTest {

    @Test
    public void duplicateStopDoesNotResetProcessingAndTokenGuardsFinalUpdates() throws Exception {
        String xml = readMapperXml();

        assertTrue(xml.contains("event_status = 'PROCESSING'"));
        assertTrue(xml.contains("event_status = 'APPLIED' and values(stop_time)"));
        assertTrue(xml.contains("id=\"claimPendingBatch\""));
        assertTrue(xml.contains("id=\"releaseExpiredProcessing\""));
        assertTrue(xml.contains("id=\"markProcessingAppliedBatch\""));
        assertTrue(xml.contains("id=\"upsertPendingBatch\""));
        assertTrue(xml.contains("event_version"));
        assertTrue(xml.contains("applied_stop_time"));
        assertTrue(xml.contains("event_version = #{event.eventVersion}"));
        assertTrue(xml.contains("where event_status = 'PROCESSING'"));
        assertTrue(xml.contains("and lock_token = #{lockToken}"));
        assertTrue(xml.contains("if(retry_count + 1 &gt;= #{maxRetry}, 'FAILED', 'PENDING')"));
    }

    private String readMapperXml() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/mapper/alarm/AlarmStopEventMapper.xml");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("missing AlarmStopEventMapper.xml");
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
