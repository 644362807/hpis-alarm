package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmInternalTestProperties;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.service.IAlarmConfigureService;
import com.hpis.alarm.transfer.RabbitMQAlarmPushProducer;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmServicePushFilterTest {

    @Mock
    private IAlarmConfigureService alarmConfigureService;

    @Mock
    private RabbitMQAlarmPushProducer pushProducer;

    private AlarmServiceImpl alarmService;

    private AlarmInternalTestProperties internalTestProperties;

    @Before
    public void setUp() {
        alarmService = new AlarmServiceImpl();
        ReflectionTestUtils.setField(alarmService, "pushOpen", true);
        ReflectionTestUtils.setField(alarmService, "requireMatchedPushConfig", true);
        ReflectionTestUtils.setField(alarmService, "iAlarmConfigureService", alarmConfigureService);
        ReflectionTestUtils.setField(alarmService, "rabbitMQAlarmPushProducer", pushProducer);
        internalTestProperties = new AlarmInternalTestProperties();
        ReflectionTestUtils.setField(alarmService, "internalTestProperties", internalTestProperties);
    }

    @Test
    public void disabledAlarmConfigureSkipsPush() {
        Alarm alarm = alarm("1");
        JSONObject payload = payload(alarm);
        AlarmConfigure configure = new AlarmConfigure();
        configure.setPushEnabled("0");
        configure.setPushMessageType("TEMP_PUSH");
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));

        alarmService.pushAlarmToPushService(payload);

        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
    }

    @Test
    public void enabledAlarmConfigureUsesPushMessageTypeWithoutChangingOldAlarmTypeText() {
        Alarm alarm = alarm("1");
        JSONObject payload = payload(alarm);
        AlarmConfigure configure = new AlarmConfigure();
        configure.setPushEnabled("1");
        configure.setPushMessageType("TEMP_PUSH");
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));

        alarmService.pushAlarmToPushService(payload);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(pushProducer).sendCustomPushMessage(captor.capture());
        assertEquals("TEMP_PUSH", captor.getValue().getString("messageType"));
        assertEquals("高温报警", captor.getValue().getString("alarmType"));
    }

    @Test
    public void missingPushMessageTypeFallsBackToAlarmTypeKey() {
        Alarm alarm = alarm("6");
        JSONObject payload = payload(alarm);
        AlarmConfigure configure = new AlarmConfigure();
        configure.setPushEnabled("1");
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "6"))
                .thenReturn(Collections.singletonList(configure));

        alarmService.pushAlarmToPushService(payload);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(pushProducer).sendCustomPushMessage(captor.capture());
        assertEquals("6", captor.getValue().getString("messageType"));
    }

    @Test
    public void remoteCallStubDoesNotSuppressPushMqByDefault() {
        internalTestProperties.setRemoteCallStubEnabled(true);
        Alarm alarm = alarm("1");
        JSONObject payload = payload(alarm);
        AlarmConfigure configure = new AlarmConfigure();
        configure.setPushEnabled("1");
        configure.setPushMessageType("10");
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));

        alarmService.pushAlarmToPushService(payload);

        verify(pushProducer).sendCustomPushMessage(any(JSONObject.class));
    }

    @Test
    public void pushMqStubSkipsPushMqOnlyWhenExplicitlyEnabled() {
        internalTestProperties.setPushMqStubEnabled(true);
        Alarm alarm = alarm("1");
        JSONObject payload = payload(alarm);
        AlarmConfigure configure = new AlarmConfigure();
        configure.setPushEnabled("1");
        configure.setPushMessageType("10");
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));

        alarmService.pushAlarmToPushService(payload);

        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
    }

    @Test
    public void alarmType10UsesEmergencyLabelWithoutBreakingConfiguredPush() {
        Alarm alarm = alarm("10");
        JSONObject payload = payload(alarm);
        AlarmConfigure configure = new AlarmConfigure();
        configure.setPushEnabled("1");
        configure.setPushMessageType("10");
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "10"))
                .thenReturn(Collections.singletonList(configure));

        alarmService.pushAlarmToPushService(payload);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(pushProducer).sendCustomPushMessage(captor.capture());
        assertEquals("10", captor.getValue().getString("messageType"));
        assertEquals("紧急报警", captor.getValue().getString("alarmType"));
    }

    @Test
    public void matchedConfigIsRequiredByDefaultPolicy() {
        Alarm alarm = alarm("1");
        JSONObject payload = payload(alarm);
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.emptyList());

        alarmService.pushAlarmToPushService(payload);

        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
    }

    @Test
    public void legacyFallbackCanBeRestoredBySwitch() {
        ReflectionTestUtils.setField(alarmService, "requireMatchedPushConfig", false);
        Alarm alarm = alarm("UNKNOWN_TYPE");
        JSONObject payload = payload(alarm);
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "UNKNOWN_TYPE"))
                .thenReturn(Collections.emptyList());

        alarmService.pushAlarmToPushService(payload);

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(pushProducer).sendCustomPushMessage(captor.capture());
        assertEquals("UNKNOWN_TYPE", captor.getValue().getString("alarmType"));
    }

    private JSONObject payload(Alarm alarm) {
        JSONObject payload = new JSONObject();
        payload.put("alarmType", alarm.getAlarmType());
        payload.put("time", "2026-07-02 10:00:00");
        payload.put("alarmOBJ", alarm);
        return payload;
    }

    private Alarm alarm(String alarmType) {
        Alarm alarm = new Alarm();
        alarm.setAlarmId(100L);
        alarm.setAlarmType(alarmType);
        alarm.setTenantId(10L);
        alarm.setSceneType("2");
        alarm.setDeviceSn("DEV-1");
        alarm.setTargetName("A区");
        return alarm;
    }
}
