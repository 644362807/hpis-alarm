package com.hpis.alarm.service.impl;

import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.mapper.AlarmConfigureMapper;
import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.domain.DeviceKeyInfoDTO;
import com.hpis.common.core.exception.CustomException;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;
import com.hpis.system.api.model.LoginUser;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmConfigureServiceImplTest {

    @Mock
    private AlarmConfigureMapper alarmConfigureMapper;
    @Mock
    private TokenService tokenService;
    @Mock
    private RedisService redisService;

    private AlarmConfigureServiceImpl service;

    @Before
    public void setUp() {
        service = new TestAlarmConfigureService(1001L);
        ReflectionTestUtils.setField(service, "alarmConfigureMapper", alarmConfigureMapper);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);
        ReflectionTestUtils.setField(service, "redisService", redisService);

        LoginUser loginUser = new LoginUser();
        loginUser.setUsername("tester");
        when(tokenService.getLoginUser()).thenReturn(loginUser);
    }

    @Test
    public void deviceIdsAreBoundForAlarmType10And6UsingCurrentTenant() throws Exception {
        DeviceKeyInfoDTO first = device(11L, 1001L, "DEV-11");
        DeviceKeyInfoDTO second = device(12L, 1001L, "DEV-12");
        when(redisService.getCacheObject(Constants.DEVICE_ID_KEY + 11L)).thenReturn(first);
        when(redisService.getCacheObject(Constants.DEVICE_ID_KEY + 12L)).thenReturn(second);

        AtomicLong ids = new AtomicLong(500L);
        doAnswer(invocation -> {
            AlarmConfigure configure = invocation.getArgument(0);
            configure.setAlarmConfigureId(ids.incrementAndGet());
            return 1;
        }).when(alarmConfigureMapper).insertAlarmConfigure(any(AlarmConfigure.class));

        service.insertAlarmConfigure(configure("10", 9999L, 11L, 12L));
        service.insertAlarmConfigure(configure("6", 9999L, 11L, 12L));

        ArgumentCaptor<AlarmConfigure> configureCaptor = ArgumentCaptor.forClass(AlarmConfigure.class);
        verify(alarmConfigureMapper, times(2)).insertAlarmConfigure(configureCaptor.capture());
        for (AlarmConfigure saved : configureCaptor.getAllValues()) {
            assertEquals(Long.valueOf(1001L), saved.getTenantId());
        }
        ArgumentCaptor<String[]> devicesCaptor = ArgumentCaptor.forClass(String[].class);
        verify(alarmConfigureMapper, times(2)).batchDeviceConfigure(devicesCaptor.capture(), anyLong());
        for (String[] deviceSns : devicesCaptor.getAllValues()) {
            assertArrayEquals(new String[]{"DEV-11", "DEV-12"}, deviceSns);
        }
    }

    @Test(expected = CustomException.class)
    public void crossTenantDeviceIsRejectedBeforeConfigInsert() throws Exception {
        when(redisService.getCacheObject(Constants.DEVICE_ID_KEY + 11L))
                .thenReturn(device(11L, 2002L, "OTHER-TENANT-DEV"));

        try {
            service.insertAlarmConfigure(configure("10", 1001L, 11L));
        } finally {
            verify(alarmConfigureMapper, never()).insertAlarmConfigure(any(AlarmConfigure.class));
        }
    }

    @Test
    public void detailReturnsOnlyCurrentTenantConfigAndDeviceRelations() {
        AlarmConfigure stored = configure("10", 1001L);
        stored.setAlarmConfigureId(501L);
        when(alarmConfigureMapper.selectAlarmConfigureById(501L, 1001L)).thenReturn(stored);
        when(alarmConfigureMapper.selectDeviceSnsByConfigureId(501L, 1001L))
                .thenReturn(Arrays.asList("DEV-11", "DEV-12"));
        when(redisService.getCacheObject(Constants.DEVICE_SN_KEY + "DEV-11"))
                .thenReturn(device(11L, 1001L, "DEV-11"));
        when(redisService.getCacheObject(Constants.DEVICE_SN_KEY + "DEV-12"))
                .thenReturn(device(12L, 1001L, "DEV-12"));

        AlarmConfigure result = service.selectAlarmConfigureById(501L);

        assertNotNull(result);
        assertArrayEquals(new Long[]{11L, 12L}, result.getDeviceIds());
        assertEquals(2, result.getDeviceSet().size());
    }

    @Test
    public void deleteOnlyCleansRelationsForIdsOwnedByCurrentTenant() {
        when(alarmConfigureMapper.selectExistingIdsByTenant(new Long[]{501L, 999L}, 1001L))
                .thenReturn(Collections.singletonList(501L));
        when(alarmConfigureMapper.deleteAlarmConfigureByIds(any(Long[].class), eq(1001L))).thenReturn(1);

        int result = service.deleteAlarmConfigureByIds(new Long[]{501L, 999L});

        assertEquals(1, result);
        ArgumentCaptor<Long[]> idsCaptor = ArgumentCaptor.forClass(Long[].class);
        verify(alarmConfigureMapper).deleteAlarmConfigureDeviceByIds(idsCaptor.capture());
        assertArrayEquals(new Long[]{501L}, idsCaptor.getValue());
        verify(alarmConfigureMapper).deleteConfigTimeByConfigureIds(any(Long[].class));
    }

    private AlarmConfigure configure(String alarmType, Long requestTenantId, Long... deviceIds) {
        AlarmConfigure configure = new AlarmConfigure();
        configure.setAlarmConfigureName("test-" + alarmType);
        configure.setAlarmType(alarmType);
        configure.setTenantId(requestTenantId);
        configure.setSceneType("1");
        configure.setDeviceAlarmControl("1");
        configure.setAlarmConfigurePeriod("0");
        configure.setPushEnabled("1");
        configure.setPushMessageType("10");
        configure.setDeviceIds(deviceIds);
        return configure;
    }

    private DeviceKeyInfoDTO device(Long deviceId, Long tenantId, String deviceSn) {
        DeviceKeyInfoDTO device = new DeviceKeyInfoDTO();
        device.setDeviceId(deviceId);
        device.setTenantId(tenantId);
        device.setDeviceSn(deviceSn);
        return device;
    }

    private static final class TestAlarmConfigureService extends AlarmConfigureServiceImpl {
        private final Long tenantId;

        private TestAlarmConfigureService(Long tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        protected Long currentTenantId() {
            return tenantId;
        }
    }
}
