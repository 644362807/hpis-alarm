package com.hpis.alarm.mapper;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class AlarmMapperXmlContractTest {

    @Test
    public void deleteScopeSelectRequiresTenantAndActiveRow() throws Exception {
        String xml = normalizeWhitespace(readResource("/mapper/alarm/AlarmMapper.xml"));
        String select = fragmentById(xml, "selectExistingIdsByTenant");

        assertTrue(select.contains("alarm_id in"));
        assertTrue(select.contains("tenant_id = #{tenantId}"));
        assertTrue(select.contains("del_flag = '0'"));
        assertTrue(select.contains("collection=\"alarmIds\""));
    }

    @Test
    public void extensionDeleteStatementsRemainLogicalDeletes() throws Exception {
        String electrolyticXml = normalizeWhitespace(
                readResource("/mapper/alarm/AlarmElectrolyticCellMapper.xml")).toLowerCase();
        String partialDischargeXml = normalizeWhitespace(
                readResource("/mapper/alarm/AlarmPartialDischargeMapper.xml")).toLowerCase();

        String electrolyticDelete = fragmentById(electrolyticXml, "deletealarmelectrolyticcellbyids");
        String partialDischargeDelete = fragmentById(partialDischargeXml, "deletealarmpartialdischargebyids");
        assertTrue(electrolyticDelete.contains("update alarm_electrolytic_cell set del_flag=2"));
        assertTrue(partialDischargeDelete.contains("update alarm_partial_discharge set del_flag=2"));
        assertFalse(electrolyticDelete.contains("delete from"));
        assertFalse(partialDischargeDelete.contains("delete from"));
    }

    @Test
    public void migrationAddsDelFlagToExtensionBaseAndMonthlyTables() throws Exception {
        String sql = normalizeWhitespace(
                readResource("/sql/alarm-extension-del-flag-migration.sql")).toLowerCase();

        assertTrue(sql.contains("alarm_electrolytic_cell"));
        assertTrue(sql.contains("alarm_partial_discharge"));
        assertTrue(sql.contains("add column del_flag char(2) not null default ''0''"));
        assertTrue(sql.contains("information_schema.columns"));
        assertTrue(sql.contains("alarm_electrolytic_cell_[0-9]{6}_[0-9]{2}"));
    }

    private String fragmentById(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        if (start < 0) {
            return "";
        }
        int selectEnd = xml.indexOf("</select>", start);
        int deleteEnd = xml.indexOf("</delete>", start);
        int end = selectEnd < 0 ? deleteEnd : deleteEnd < 0 ? selectEnd : Math.min(selectEnd, deleteEnd);
        return end < 0 ? xml.substring(start) : xml.substring(start, end);
    }

    private String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private String readResource(String path) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("missing " + path);
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
