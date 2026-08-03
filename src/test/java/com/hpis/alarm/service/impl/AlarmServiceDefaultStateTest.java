package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.domain.Alarm;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** 新报警的单条、批量入库必须共享相同的初始状态。 */
public class AlarmServiceDefaultStateTest {

    @Test
    public void jsonTransformJavaShouldInitializeNewAlarmAsActiveAndNotDeleted() throws Exception {
        AlarmServiceImpl service = new AlarmServiceImpl();
        Alarm alarm = new Alarm();

        service.jsonTransformJava(new JSONObject(), alarm);

        assertEquals("0", alarm.getAlarmStatus());
        assertEquals("0", alarm.getDelFlag());
    }
}
