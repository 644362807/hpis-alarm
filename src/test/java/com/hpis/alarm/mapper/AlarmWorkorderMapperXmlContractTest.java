package com.hpis.alarm.mapper;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AlarmWorkorderMapperXmlContractTest {

    @Test
    public void alarmConfigureMapperCarriesPushAndWorkorderConfigContract() throws Exception {
        String xml = normalizeWhitespace(readMapperXml("AlarmConfigure.xml"));
        String selectSql = fragmentById(xml, "selectAlarmConfigureVo");
        String insertSql = fragmentById(xml, "insertAlarmConfigure");
        String updateSql = fragmentById(xml, "updateAlarmConfigure");
        String listSql = fragmentById(xml, "selectAlarmConfigureList");
        String selectEnabledForAlarmSql = fragmentById(xml, "selectEnabledForAlarm");

        assertTrue(selectSql.contains("push_enabled"));
        assertTrue(selectSql.contains("push_message_type"));
        assertTrue(selectSql.contains("workorder_push_message_type"));
        assertTrue(selectSql.contains("workorder_config_id"));
        assertTrue(insertSql.contains("push_enabled,"));
        assertTrue(insertSql.contains("push_message_type,"));
        assertTrue(insertSql.contains("workorder_push_message_type,"));
        assertTrue(insertSql.contains("workorder_config_id,"));
        assertTrue(updateSql.contains("push_enabled = #{pushEnabled}"));
        assertTrue(updateSql.contains("push_message_type = #{pushMessageType}"));
        assertTrue(updateSql.contains("workorder_push_message_type = #{workorderPushMessageType}"));
        assertTrue(updateSql.contains("workorder_config_id = #{workorderConfigId}"));
        assertTrue(selectEnabledForAlarmSql.contains("c.push_enabled"));
        assertTrue(selectEnabledForAlarmSql.contains("c.push_message_type"));
        assertTrue(selectEnabledForAlarmSql.contains("c.workorder_push_message_type"));
        assertTrue(selectEnabledForAlarmSql.contains("c.workorder_config_id"));
        assertTrue(selectEnabledForAlarmSql.contains("join alarm_device_configure adc on adc.alarm_configure_id = c.alarm_configure_id"));
        assertTrue(selectEnabledForAlarmSql.contains("and adc.device_sn is not null"));
        assertTrue(selectEnabledForAlarmSql.contains("and ( adc.device_sn = #{deviceSn} or adc.device_sn in ('ALL', '*') )"));
        assertTrue(selectEnabledForAlarmSql.contains("case when adc.device_sn = #{deviceSn} then 0"));
        assertTrue(selectEnabledForAlarmSql.contains("when adc.device_sn in ('ALL', '*') then 1"));
        assertFalse(selectSql.contains("device_sn"));
        assertFalse(containsStandaloneSqlFragment(selectEnabledForAlarmSql, "c.device_sn"));
        assertTrue(listSql.contains("from alarm_device_configure adc"));
        assertTrue(listSql.contains("adc.device_sn = #{deviceSn}"));
        assertFalse(containsStandaloneSqlFragment(selectEnabledForAlarmSql, "c.device_sn = #{deviceSn}"));
        assertFalse(containsStandaloneSqlFragment(selectEnabledForAlarmSql, "c.device_sn is null"));
        assertFalse(containsStandaloneSqlFragment(selectEnabledForAlarmSql, "c.device_sn = ''"));
        assertFalse(containsStandaloneSqlFragment(selectEnabledForAlarmSql, "c.device_sn in ('ALL', '*')"));
    }

    @Test
    public void alarmConfigureMapperUsesDeviceBindingTableForSaveAndDuplicateCheck() throws Exception {
        String xml = normalizeWhitespace(readMapperXml("AlarmConfigure.xml"));
        String baseResultMap = fragmentById(xml, "AlarmConfigureResult");
        String deviceResultMap = fragmentById(xml, "AlarmConfigureForAlarmResult");
        String insertSql = fragmentById(xml, "insertAlarmConfigure");
        String updateSql = fragmentById(xml, "updateAlarmConfigure");
        String duplicateCheckSql = fragmentById(xml, "countEnabledDuplicateConfigureByDevices");
        String selectDeviceSnsSql = fragmentById(xml, "selectDeviceSnsByConfigureId");

        assertFalse(xml.contains("id=\"countEnabledDuplicateConfigure\""));
        assertFalse(baseResultMap.contains("column=\"device_sn\""));
        assertTrue(deviceResultMap.contains("column=\"device_sn\""));
        assertTrue(xml.contains("id=\"countEnabledDuplicateConfigureByDevices\""));
        assertTrue(duplicateCheckSql.contains("from alarm_configure c join alarm_device_configure adc"));
        assertTrue(duplicateCheckSql.contains("and adc.device_sn is not null"));
        assertTrue(duplicateCheckSql.contains("adc.device_sn in ('ALL', '*')"));
        assertTrue(duplicateCheckSql.contains("<foreach collection=\"deviceSns\""));
        assertFalse(insertSql.contains("device_sn,"));
        assertFalse(insertSql.contains("#{deviceSn},"));
        assertFalse(updateSql.contains("device_sn = #{deviceSn}"));
        assertFalse(duplicateCheckSql.contains("device_sn = #{deviceSn}"));
        assertTrue(selectDeviceSnsSql.contains("from alarm_device_configure adc join alarm_configure c"));
        assertTrue(selectDeviceSnsSql.contains("adc.alarm_configure_id = #{alarmConfigureId}"));
        assertTrue(selectDeviceSnsSql.contains("c.tenant_id = #{tenantId}"));
        assertTrue(selectDeviceSnsSql.contains("c.del_flag = '0'"));
    }

    @Test
    public void alarmHandleMapperCarriesWorkorderIdWithoutProcessFields() throws Exception {
        String xml = normalizeWhitespace(readMapperXml("AlarmHandleMapper.xml"));
        String resultMap = fragmentById(xml, "AlarmHandleResult");
        String insertSql = fragmentById(xml, "insertAlarmHandle");
        String updateSql = fragmentById(xml, "updateAlarmHandle");

        assertTrue(resultMap.contains("property=\"workorderId\" column=\"workorder_id\""));
        assertTrue(insertSql.contains("workorder_id,"));
        assertTrue(insertSql.contains("#{workorderId}"));
        assertTrue(updateSql.contains("workorder_id = #{workorderId}"));
        assertFalse(xml.contains("status_before"));
        assertFalse(xml.contains("status_after"));
        assertFalse(xml.contains("handle_type"));
    }

    @Test
    public void alarmWorkorderMapperStoresCurrentWorkorderOnly() throws Exception {
        String xml = normalizeWhitespace(readMapperXml("AlarmWorkorderMapper.xml"));

        assertTrue(xml.contains("namespace=\"com.hpis.alarm.mapper.AlarmWorkorderMapper\""));
        assertTrue(xml.contains("uk_alarm_workorder_alarm"));
        assertTrue(xml.contains("selectAlarmWorkorderByAlarmIdAndTenant"));
        assertFalse(xml.contains("id=\"selectAlarmWorkorderById\""));
        assertTrue(xml.contains("insertAlarmWorkorder"));
        assertTrue(xml.contains("updateEditableByIdAndTenant"));
        assertFalse(xml.contains("alarm_workorder_flow"));
        assertFalse(xml.contains("status_before"));
        assertFalse(xml.contains("status_after"));
    }

    @Test
    public void alarmWorkorderMapperEnforcesTenantOwnerAndAtomicLifecycleGuards() throws Exception {
        String xml = normalizeWhitespace(readMapperXml("AlarmWorkorderMapper.xml"));
        String allPageSql = fragmentById(xml, "selectAlarmWorkorderPage");
        String myPageSql = fragmentById(xml, "selectMyAlarmWorkorderPage");
        String editableSql = fragmentById(xml, "updateEditableByIdAndTenant");
        String transferSql = fragmentById(xml, "updateAssigneeByIdAndTenant");
        String completeSql = fragmentById(xml, "completeByIdAndOwner");
        String closeSql = fragmentById(xml, "closeByIdAndTenant");
        String deleteSql = fragmentById(xml, "deleteByIdsAndTenant");

        assertTrue(allPageSql.contains("tenant_id = #{tenantId}"));
        assertTrue(myPageSql.contains("tenant_id = #{tenantId}"));
        assertTrue(myPageSql.contains("assignee_id = #{assigneeId}"));
        assertTrue(myPageSql.contains("assignee_id &gt; 0"));

        assertTrue(editableSql.contains("tenant_id = #{tenantId}"));
        assertTrue(editableSql.contains("status not in ('2', '3')"));
        assertFalse(editableSql.contains("assignee_id ="));
        assertFalse(editableSql.contains("tenant_id = #{workorder.tenantId}"));
        assertFalse(editableSql.contains("status = #{workorder.status}"));

        assertTrue(transferSql.contains("status not in ('2', '3')"));
        assertTrue(transferSql.contains("status = '0'"));
        assertFalse(transferSql.contains("status = #{workorder.status}"));

        assertTrue(completeSql.contains("assignee_id = #{assigneeId}"));
        assertTrue(completeSql.contains("status in ('0', '1')"));
        assertTrue(completeSql.contains("status = '2'"));
        assertTrue(closeSql.contains("status not in ('2', '3')"));
        assertTrue(closeSql.contains("status = '3'"));
        assertTrue(deleteSql.contains("tenant_id = #{tenantId}"));
        assertTrue(deleteSql.contains("status not in ('2', '3')"));
    }

    @Test
    public void alarmHandleMapperSupportsOneBatchPictureLookup() throws Exception {
        String xml = normalizeWhitespace(readMapperXml("AlarmHandleMapper.xml"));
        String resultMap = fragmentById(xml, "AlarmHandleResult");
        String batchSql = fragmentById(xml, "selectAlarmHandlesByAlarmIds");

        assertTrue(resultMap.contains("property=\"handlePicture\" column=\"handle_picture\""));
        assertTrue(batchSql.contains("handle_picture"));
        assertTrue(batchSql.contains("alarm_id in"));
        assertTrue(batchSql.contains("<foreach collection=\"alarmIds\""));
    }

    @Test
    public void migrationSqlDoesNotAddAlarmHandleProcessFields() throws Exception {
        String sql = normalizeWhitespace(readResource("/sql/alarm-configure-workorder-migration.sql"));

        assertTrue(sql.contains("push_enabled"));
        assertTrue(sql.contains("push_message_type"));
        assertTrue(sql.contains("workorder_push_message_type"));
        assertTrue(sql.contains("workorder_config_id"));
        assertTrue(sql.contains("workorder_id"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `alarm_workorder`"));
        assertTrue(sql.contains("idx_alarm_workorder_tenant_assignee_status"));
        assertFalse(sql.contains("handle_type"));
        assertFalse(sql.contains("status_before"));
        assertFalse(sql.contains("status_after"));
        assertFalse(sql.contains("alarm_workorder_flow"));
    }

    private String fragmentById(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        if (start < 0) {
            return "";
        }
        int end = firstClosingTagAfter(xml, start);
        if (end < 0) {
            return xml.substring(start);
        }
        return xml.substring(start, end);
    }

    private int firstClosingTagAfter(String xml, int start) {
        int end = -1;
        String[] closingTags = {"</resultMap>", "</sql>", "</select>", "</insert>", "</update>", "</delete>"};
        for (String closingTag : closingTags) {
            int candidate = xml.indexOf(closingTag, start);
            if (candidate >= 0 && (end < 0 || candidate < end)) {
                end = candidate;
            }
        }
        return end;
    }

    private String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private boolean containsStandaloneSqlFragment(String xml, String fragment) {
        int index = xml.indexOf(fragment);
        while (index >= 0) {
            if (index == 0 || !Character.isJavaIdentifierPart(xml.charAt(index - 1))) {
                return true;
            }
            index = xml.indexOf(fragment, index + 1);
        }
        return false;
    }

    private String readMapperXml(String fileName) throws Exception {
        return readResource("/mapper/alarm/" + fileName);
    }

    private String readResource(String resourcePath) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resourcePath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("missing " + resourcePath);
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
