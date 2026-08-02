package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.domain.Alarm;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AlarmPushMessageLevelTest {

    @Test
    public void rankedAlarmAddsTrimmedMessageLevel() {
        Alarm alarm = new Alarm();
        alarm.setAlarmRank(" 2 ");
        JSONObject message = new JSONObject();

        ReflectionTestUtils.invokeMethod(
                new AlarmServiceImpl(), "putMessageLevel", message, alarm);

        assertEquals("2", message.getString("messageLevel"));
    }

    @Test
    public void unrankedAlarmDoesNotCreateMessageLevel() {
        Alarm alarm = new Alarm();
        alarm.setAlarmRank(" ");
        JSONObject message = new JSONObject();

        ReflectionTestUtils.invokeMethod(
                new AlarmServiceImpl(), "putMessageLevel", message, alarm);

        assertFalse(message.containsKey("messageLevel"));
    }
}
