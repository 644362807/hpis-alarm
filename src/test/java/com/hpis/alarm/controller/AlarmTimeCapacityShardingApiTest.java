package com.hpis.alarm.controller;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.service.IAlarmHandleService;
import com.hpis.alarm.service.IAlarmService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.junit.runner.RunWith;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.text.SimpleDateFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * alarm 时间 + 容量分片相关接口契约测试。
 *
 * <p>这里使用 standalone MockMvc，不启动完整 Spring 容器，也不连接数据库。测试重点是验证旧接口入参
     * 仍能被 Controller 正确接收并透传给 Service：新增报警需要保留 time/alarmId(device 外部 cid)/deviceSn/irmsSn，
 * 停止报警需要保留 deviceSn/irmsSn/time，处理表直接新增需要能接收 alarm_beginTime 对应的
 * alarmBegintime 字段。真正的落库路由由 Service 和分片算法测试覆盖。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class AlarmTimeCapacityShardingApiTest {

    private static final String ALARM_TIME = "2026-04-15 10:20:30";

    @Mock
    private IAlarmService alarmService;

    @Mock
    private IAlarmHandleService alarmHandleService;

    private MockMvc alarmMockMvc;

    private MockMvc handleMockMvc;

    @Before
    public void setUp() {
        AlarmController alarmController = new AlarmController();
        ReflectionTestUtils.setField(alarmController, "alarmService", alarmService);
        alarmMockMvc = MockMvcBuilders.standaloneSetup(alarmController).build();

        AlarmHandleController alarmHandleController = new AlarmHandleController();
        ReflectionTestUtils.setField(alarmHandleController, "alarmHandleService", alarmHandleService);
        handleMockMvc = MockMvcBuilders.standaloneSetup(alarmHandleController).build();
    }

    @Test
    public void alarmAddKeepsLegacyKeysAndTimeForCidHotRoute() throws Exception {
        String body = "{"
                + "\"alarmId\":\"cid-202604-001\","
                + "\"time\":\"" + ALARM_TIME + "\","
                + "\"deviceSn\":\"device-001\","
                + "\"irmsSn\":\"irms-001\","
                + "\"sceneType\":2,"
                + "\"alarmType\":\"1\","
                + "\"alarmDegree\":\"2\""
                + "}";

        alarmMockMvc.perform(post("/alarm/alarmAdd")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(alarmService).insertAlarm(captor.capture());
        JSONObject payload = captor.getValue();
        assertThat(payload.getString("alarmId")).isEqualTo("cid-202604-001");
        assertThat(payload.getString("time")).isEqualTo(ALARM_TIME);
        assertThat(payload.getString("deviceSn")).isEqualTo("device-001");
        assertThat(payload.getString("irmsSn")).isEqualTo("irms-001");
        assertThat(payload.getIntValue("sceneType")).isEqualTo(2);
    }

    @Test
    public void alarmStopByDeviceSnKeepsDeviceAndStopTimeForCidRoute() throws Exception {
        String body = "{"
                + "\"deviceSn\":\"device-001\","
                + "\"time\":\"" + ALARM_TIME + "\""
                + "}";

        alarmMockMvc.perform(post("/alarm/alarmStopByDeviceSn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(alarmService).alarmStopByDeviceSn(captor.capture());
        JSONObject payload = captor.getValue();
        assertThat(payload.getString("deviceSn")).isEqualTo("device-001");
        assertThat(payload.getString("time")).isEqualTo(ALARM_TIME);
    }

    @Test
    public void alarmStopByIrmsSnKeepsIrmsAndStopTimeForCidRoute() throws Exception {
        when(alarmService.alarmStopByIrmsSn(any(JSONObject.class))).thenReturn(1);
        String body = "{"
                + "\"irmsSn\":\"irms-001\","
                + "\"time\":\"" + ALARM_TIME + "\""
                + "}";

        alarmMockMvc.perform(post("/alarm/alarmStopByIrmsSn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<JSONObject> captor = ArgumentCaptor.forClass(JSONObject.class);
        verify(alarmService).alarmStopByIrmsSn(captor.capture());
        JSONObject payload = captor.getValue();
        assertThat(payload.getString("irmsSn")).isEqualTo("irms-001");
        assertThat(payload.getString("time")).isEqualTo(ALARM_TIME);
    }

    @Test
    public void alarmHandleAddAcceptsAlarmBeginTimeForBoundTableRouting() throws Exception {
        when(alarmHandleService.insertAlarmHandle(any(AlarmHandle.class))).thenReturn(1);
        String body = "{"
                + "\"alarmId\":202604150001,"
                + "\"alarmBegintime\":\"" + ALARM_TIME + "\","
                + "\"handleStatus\":\"0\""
                + "}";

        handleMockMvc.perform(post("/handle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<AlarmHandle> captor = ArgumentCaptor.forClass(AlarmHandle.class);
        verify(alarmHandleService).insertAlarmHandle(captor.capture());
        AlarmHandle alarmHandle = captor.getValue();
        assertThat(alarmHandle.getAlarmId()).isEqualTo(202604150001L);
        assertThat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(alarmHandle.getAlarmBegintime())).isEqualTo(ALARM_TIME);
    }
}
