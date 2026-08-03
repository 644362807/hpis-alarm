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
    public void alarmPageContractReturnsHandleStatusFromHandleTable() throws Exception {
        String xml = normalizeWhitespace(readResource("/mapper/alarm/AlarmMapper.xml"));
        String resultMap = fragmentById(xml, "AlarmResult");
        String selectVo = fragmentById(xml, "selectAlarmVo");

        assertTrue(resultMap.contains("property=\"handleStatus\" column=\"handle_status\""));
        assertTrue(selectVo.contains("h.handle_status"));
        assertTrue(selectVo.contains("left join alarm_handle h on a.alarm_id = h.alarm_id"));
    }

    @Test
    public void alarmListsUseStableBeginTimeAndIdOrdering() throws Exception {
        String xml = normalizeWhitespace(readResource("/mapper/alarm/AlarmMapper.xml"));

        assertTrue(fragmentById(xml, "selectAlarmListPage")
                .contains("order by a.alarm_beginTime desc, a.alarm_id desc"));
        assertTrue(fragmentById(xml, "selectAlarmList")
                .contains("order by a.alarm_beginTime desc, a.alarm_id desc"));
    }

    @Test
    public void alarmCountByTimeSupportsMissingStartTime() throws Exception {
        String xml = normalizeWhitespace(readResource("/mapper/alarm/AlarmMapper.xml"));
        String select = fragmentById(xml, "alarmCountByTime");

        assertTrue(select.contains("COUNT(*) AS total_count_custom_range"));
        assertFalse(select.contains("SUM(CASE WHEN alarm_beginTime > #{alarm.startTime}"));
        assertTrue(select.contains("alarm_beginTime &lt; #{alarm.endTime}"));
    }

    @Test
    public void alarmStatisticsKeepShardColumnOnLeftOfEndPredicate() throws Exception {
        String xml = normalizeWhitespace(readResource("/mapper/alarm/AlarmMapper.xml"));
        String electrolyticXml = normalizeWhitespace(
                readResource("/mapper/alarm/AlarmElectrolyticCellMapper.xml"));

        assertTrue(fragmentById(xml, "selectAlarmByQueryParameter")
                .contains("a.alarm_beginTime &lt; #{endTime}"));
        assertTrue(fragmentById(xml, "alarmOfDay")
                .contains("alarm_beginTime &lt; #{endTime}"));
        assertTrue(fragmentById(xml, "countNoHandelOfDay")
                .contains("a.alarm_beginTime &lt; #{endTime}"));
        assertTrue(fragmentById(xml, "countAlarmMode")
                .contains("alarm_beginTime &lt; #{endTime}"));
        assertTrue(fragmentById(xml, "alarmCountByTime")
                .contains("alarm_beginTime &lt; #{alarm.endTime}"));
        assertTrue(fragmentById(electrolyticXml, "selectNewAlarmElectrolyticCellList")
                .contains("a.alarm_beginTime &lt; #{endTime}"));
    }

    @Test
    public void alarmHandlingUsesTenantAndActiveStateGuards() throws Exception {
        String alarmXml = normalizeWhitespace(readResource("/mapper/alarm/AlarmMapper.xml"));
        String workorderXml = normalizeWhitespace(readResource("/mapper/alarm/AlarmWorkorderMapper.xml"));
        String validateSql = fragmentById(alarmXml, "selectProcessableIdsByTenant");
        String handleSql = fragmentById(alarmXml, "handleActiveByIdsAndTenant");
        String workorderSql = fragmentById(workorderXml, "completeActiveByAlarmIds");
        String stopWorkorderSql = fragmentById(workorderXml, "closeActiveByAlarmIds");

        assertTrue(validateSql.contains("a.tenant_id = #{tenantId}"));
        assertTrue(validateSql.contains("a.alarm_status = '0'"));
        assertTrue(validateSql.contains("h.handle_status = '2'"));
        assertTrue(handleSql.contains("tenant_id = #{tenantId}"));
        assertTrue(handleSql.contains("alarm_status = '0'"));
        assertTrue(workorderSql.contains("tenant_id = #{tenantId}"));
        assertTrue(workorderSql.contains("status in ('0', '1')"));
        assertTrue(workorderSql.contains("status = '2'"));
        assertTrue(stopWorkorderSql.contains("status = '3'"));
        assertTrue(stopWorkorderSql.contains("status in ('0', '1')"));
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
        int sqlEnd = xml.indexOf("</sql>", start);
        int resultMapEnd = xml.indexOf("</resultMap>", start);
        int deleteEnd = xml.indexOf("</delete>", start);
        int end = firstPositive(selectEnd, sqlEnd, resultMapEnd, deleteEnd);
        return end < 0 ? xml.substring(start) : xml.substring(start, end);
    }

    private int firstPositive(int... values) {
        int result = -1;
        for (int value : values) {
            if (value >= 0 && (result < 0 || value < result)) {
                result = value;
            }
        }
        return result;
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
