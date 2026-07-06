package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.domain.AlarmWorkorder;
import com.hpis.alarm.enums.HandleStatusEnums;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.mapper.AlarmWorkorderMapper;
import com.hpis.alarm.service.IAlarmConfigureService;
import com.hpis.alarm.transfer.RabbitMQAlarmPushProducer;
import com.hpis.common.core.exception.CustomException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmWorkorderServiceImplTest {

    @Mock
    private AlarmWorkorderMapper workorderMapper;

    @Mock
    private AlarmMapper alarmMapper;

    @Mock
    private AlarmHandleMapper alarmHandleMapper;

    @Mock
    private IAlarmConfigureService alarmConfigureService;

    @Mock
    private RabbitMQAlarmPushProducer pushProducer;

    private AlarmWorkorderServiceImpl service;

    @Before
    public void setUp() {
        clearTransactionSynchronization();
        service = new AlarmWorkorderServiceImpl();
        ReflectionTestUtils.setField(service, "alarmWorkorderMapper", workorderMapper);
        ReflectionTestUtils.setField(service, "alarmMapper", alarmMapper);
        ReflectionTestUtils.setField(service, "alarmHandleMapper", alarmHandleMapper);
        ReflectionTestUtils.setField(service, "alarmConfigureService", alarmConfigureService);
        ReflectionTestUtils.setField(service, "rabbitMQAlarmPushProducer", pushProducer);
        ReflectionTestUtils.setField(service, "pushOpen", false);
    }

    @After
    public void tearDown() {
        clearTransactionSynchronization();
    }

    @Test
    public void createWorkorderRequiresConfirmedAlarmAndConfiguredTemplate() {
        Alarm alarm = alarm();
        AlarmHandle confirmedHandle = new AlarmHandle();
        confirmedHandle.setHandleStatus(HandleStatusEnums.ALARM_STATUS_ENUMS_2.getKey());
        AlarmConfigure configure = new AlarmConfigure();
        configure.setWorkorderConfigId(900L);
        AlarmWorkorder request = new AlarmWorkorder();
        request.setAlarmId(200L);
        request.setWorkorderNo("WO-200");

        when(alarmMapper.selectAlarmById(200L)).thenReturn(alarm);
        when(alarmHandleMapper.selectAlarmHandleByAlarmId(200L)).thenReturn(confirmedHandle);
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));
        when(workorderMapper.insertAlarmWorkorder(any(AlarmWorkorder.class))).thenReturn(1);

        int result = service.createWorkorder(request);

        ArgumentCaptor<AlarmWorkorder> captor = ArgumentCaptor.forClass(AlarmWorkorder.class);
        verify(workorderMapper).insertAlarmWorkorder(captor.capture());
        assertEquals(1, result);
        assertEquals(Long.valueOf(900L), captor.getValue().getWorkorderConfigId());
        assertEquals(Long.valueOf(10L), captor.getValue().getTenantId());
        assertEquals("0", captor.getValue().getDelFlag());
        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
        verify(alarmHandleMapper, never()).updateAlarmHandle(any(AlarmHandle.class));
    }

    @Test
    public void createWorkorderPublishesConfiguredWorkorderPushAfterCommit() {
        ReflectionTestUtils.setField(service, "pushOpen", true);
        Alarm alarm = alarm();
        AlarmHandle confirmedHandle = confirmedHandle();
        AlarmConfigure configure = configuredWorkorder(900L, "25");
        AlarmWorkorder request = request();

        when(alarmMapper.selectAlarmById(200L)).thenReturn(alarm);
        when(alarmHandleMapper.selectAlarmHandleByAlarmId(200L)).thenReturn(confirmedHandle);
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));
        when(workorderMapper.insertAlarmWorkorder(any(AlarmWorkorder.class))).thenAnswer(invocation -> {
            AlarmWorkorder saved = invocation.getArgument(0);
            saved.setWorkorderId(300L);
            return 1;
        });

        TransactionSynchronizationManager.initSynchronization();
        int result = service.createWorkorder(request);

        assertEquals(1, result);
        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(pushProducer).sendCustomPushMessage(captor.capture());
        JSONObject pushMessage = captor.getValue();
        assertEquals("25", pushMessage.getString("messageType"));
        assertEquals(Long.valueOf(10L), pushMessage.getLong("tenantId"));
        assertEquals("DEV-1", pushMessage.getString("deviceSn"));
        assertEquals(Long.valueOf(200L), pushMessage.getLong("alarmId"));
        assertEquals(Long.valueOf(300L), pushMessage.getLong("workorderId"));
        assertEquals("WO-200", pushMessage.getString("workorderNo"));
        assertEquals(Long.valueOf(900L), pushMessage.getLong("workorderConfigId"));
        assertEquals("0", pushMessage.getString("status"));
        assertEquals("ALARM_WORKORDER_CREATED", pushMessage.getJSONObject("data").getString("eventType"));
    }

    @Test
    public void createWorkorderWithoutWorkorderPushMessageTypeDoesNotPush() {
        ReflectionTestUtils.setField(service, "pushOpen", true);
        Alarm alarm = alarm();
        AlarmConfigure configure = configuredWorkorder(900L, null);
        AlarmWorkorder request = request();

        when(alarmMapper.selectAlarmById(200L)).thenReturn(alarm);
        when(alarmHandleMapper.selectAlarmHandleByAlarmId(200L)).thenReturn(confirmedHandle());
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));
        when(workorderMapper.insertAlarmWorkorder(any(AlarmWorkorder.class))).thenReturn(1);

        assertEquals(1, service.createWorkorder(request));

        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
    }

    @Test
    public void createWorkorderDoesNotPushWhenPushOpenDisabled() {
        Alarm alarm = alarm();
        AlarmConfigure configure = configuredWorkorder(900L, "25");
        AlarmWorkorder request = request();

        when(alarmMapper.selectAlarmById(200L)).thenReturn(alarm);
        when(alarmHandleMapper.selectAlarmHandleByAlarmId(200L)).thenReturn(confirmedHandle());
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));
        when(workorderMapper.insertAlarmWorkorder(any(AlarmWorkorder.class))).thenReturn(1);

        assertEquals(1, service.createWorkorder(request));

        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
    }

    @Test
    public void createWorkorderDoesNotPushOnDuplicateKey() {
        ReflectionTestUtils.setField(service, "pushOpen", true);
        Alarm alarm = alarm();
        AlarmConfigure configure = configuredWorkorder(900L, "25");
        AlarmWorkorder request = request();

        when(alarmMapper.selectAlarmById(200L)).thenReturn(alarm);
        when(alarmHandleMapper.selectAlarmHandleByAlarmId(200L)).thenReturn(confirmedHandle());
        when(alarmConfigureService.selectEnabledForAlarm(10L, "2", "DEV-1", "1"))
                .thenReturn(Collections.singletonList(configure));
        when(workorderMapper.insertAlarmWorkorder(any(AlarmWorkorder.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        try {
            service.createWorkorder(request);
            fail("duplicate workorder should throw CustomException");
        } catch (CustomException expected) {
            // expected
        }

        verify(pushProducer, never()).sendCustomPushMessage(any(JSONObject.class));
    }

    @Test
    public void transferWorkorderOnlyUpdatesCurrentWorkorder() {
        AlarmWorkorder request = new AlarmWorkorder();
        request.setWorkorderId(300L);
        request.setStatus("1");
        request.setAssigneeId(88L);
        request.setAssigneeName("张三");
        when(workorderMapper.updateAlarmWorkorder(any(AlarmWorkorder.class))).thenReturn(1);

        int result = service.transferWorkorder(request);

        assertEquals(1, result);
        verify(workorderMapper).updateAlarmWorkorder(request);
        verify(alarmHandleMapper, never()).insertAlarmHandle(any(AlarmHandle.class));
        verify(alarmHandleMapper, never()).updateAlarmHandle(any(AlarmHandle.class));
    }

    @Test
    public void completeWorkorderWritesFinalAlarmHandleWithWorkorderId() {
        AlarmWorkorder request = new AlarmWorkorder();
        request.setWorkorderId(300L);
        request.setAlarmId(200L);
        request.setHandleResult("现场已处理");
        when(workorderMapper.updateAlarmWorkorder(any(AlarmWorkorder.class))).thenReturn(1);
        when(alarmHandleMapper.updateAlarmHandle(any(AlarmHandle.class))).thenReturn(1);

        int result = service.completeWorkorder(request);

        ArgumentCaptor<AlarmHandle> captor = ArgumentCaptor.forClass(AlarmHandle.class);
        verify(alarmHandleMapper).updateAlarmHandle(captor.capture());
        assertEquals(1, result);
        assertEquals(Long.valueOf(300L), captor.getValue().getWorkorderId());
        assertEquals(Long.valueOf(200L), captor.getValue().getAlarmId());
        assertEquals("现场已处理", captor.getValue().getOpinion());
        assertEquals(HandleStatusEnums.ALARM_STATUS_ENUMS_1.getKey(), captor.getValue().getHandleStatus());
    }

    private Alarm alarm() {
        Alarm alarm = new Alarm();
        alarm.setAlarmId(200L);
        alarm.setTenantId(10L);
        alarm.setSceneType("2");
        alarm.setDeviceSn("DEV-1");
        alarm.setAlarmType("1");
        return alarm;
    }

    private AlarmHandle confirmedHandle() {
        AlarmHandle confirmedHandle = new AlarmHandle();
        confirmedHandle.setHandleStatus(HandleStatusEnums.ALARM_STATUS_ENUMS_2.getKey());
        return confirmedHandle;
    }

    private AlarmConfigure configuredWorkorder(Long workorderConfigId, String workorderPushMessageType) {
        AlarmConfigure configure = new AlarmConfigure();
        configure.setWorkorderConfigId(workorderConfigId);
        configure.setWorkorderPushMessageType(workorderPushMessageType);
        return configure;
    }

    private AlarmWorkorder request() {
        AlarmWorkorder request = new AlarmWorkorder();
        request.setAlarmId(200L);
        request.setWorkorderNo("WO-200");
        return request;
    }

    private void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
