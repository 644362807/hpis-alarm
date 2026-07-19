package com.hpis.alarm.mapper;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class AlarmHandleMapperXmlContractTest {

    @Test
    public void pageQueryCarriesHandleStatusWrapperAndStableOrder() throws Exception {
        String xml = normalizeWhitespace(readResource("/mapper/alarm/AlarmHandleMapper.xml"));
        String selectVo = fragmentById(xml, "selectAlarmHandleVo");
        String pageQuery = fragmentById(xml, "selectAlarmHandlePage");

        assertTrue(selectVo.contains("h.handle_status"));
        assertFalse(selectVo.contains("a.device_id"));
        assertTrue(pageQuery.contains("<include refid=\"selectAlarmHandleVo\""));
        assertTrue(pageQuery.contains("${ew.sqlSegment}"));
        assertTrue(pageQuery.contains("order by a.alarm_beginTime desc,a.alarm_id desc"));
    }

    private String fragmentById(String xml, String id) {
        int start = xml.indexOf("id=\"" + id + "\"");
        if (start < 0) {
            return "";
        }
        int end = xml.indexOf("</select>", start);
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
