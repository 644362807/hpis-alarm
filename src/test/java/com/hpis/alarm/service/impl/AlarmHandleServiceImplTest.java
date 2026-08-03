package com.hpis.alarm.service.impl;

import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.dto.HandleParamDto;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmElectrolyticCellMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.mapper.AlarmWorkorderMapper;
import com.hpis.common.core.exception.CustomException;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import com.hpis.system.api.model.LoginUser;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@RunWith(MockitoJUnitRunner.class)
public class AlarmHandleServiceImplTest {

    @Mock
    private AlarmHandleMapper alarmHandleMapper;

    @Mock
    private TokenService tokenService;

    @Mock
    private RedisService redisService;

    @Mock
    private AlarmMapper alarmMapper;

    @Mock
    private AlarmWorkorderMapper alarmWorkorderMapper;

    @Mock
    private AlarmElectrolyticCellMapper alarmElectrolyticCellMapper;

    private AlarmHandleServiceImpl alarmHandleService;

    @Before
    public void setUp() {
        alarmHandleService = new TestAlarmHandleServiceImpl();
        ReflectionTestUtils.setField(alarmHandleService, "alarmHandleMapper", alarmHandleMapper);
        ReflectionTestUtils.setField(alarmHandleService, "tokenService", tokenService);
        ReflectionTestUtils.setField(alarmHandleService, "redisService", redisService);
        ReflectionTestUtils.setField(alarmHandleService, "alarmMapper", alarmMapper);
        ReflectionTestUtils.setField(alarmHandleService, "alarmWorkorderMapper", alarmWorkorderMapper);
        ReflectionTestUtils.setField(alarmHandleService, "alarmElectrolyticCellMapper", alarmElectrolyticCellMapper);
    }

    @Test
    public void confirmedHandleRejectsExpiredLoginBeforeWritingAnything() {
        AlarmHandle alarmHandle = new AlarmHandle();
        alarmHandle.setAlarmIds(new Long[]{101L});
        alarmHandle.setHandleStatus("2");
        when(tokenService.getLoginUser()).thenReturn(null);

        try {
            alarmHandleService.updateAlarmHandle(alarmHandle);
            fail("expired login must be rejected explicitly");
        } catch (CustomException ex) {
            assertEquals("登录状态已失效", ex.getMessage());
        }

        verifyNoInteractions(redisService, alarmHandleMapper);
    }

    @Test
    public void confirmedHandleRejectsAlarmOutsideCurrentTenantBeforeWritingAnything() {
        AlarmHandle alarmHandle = new AlarmHandle();
        alarmHandle.setAlarmIds(new Long[]{101L});
        alarmHandle.setHandleStatus("2");
        LoginUser user = new LoginUser();
        user.setUserid(55L);
        when(tokenService.getLoginUser()).thenReturn(user);
        when(alarmMapper.selectExistingIdsByTenant(new Long[]{101L}, 10L))
                .thenReturn(Collections.emptyList());

        try {
            alarmHandleService.updateAlarmHandle(alarmHandle);
            fail("cross-tenant alarm confirmation must be rejected");
        } catch (CustomException expected) {
            // expected
        }

        verifyNoInteractions(redisService, alarmHandleMapper);
    }

    @Test
    public void genericHandleUpdateAlsoRejectsAlarmOutsideCurrentTenant() {
        AlarmHandle alarmHandle = new AlarmHandle();
        alarmHandle.setAlarmIds(new Long[]{102L});
        alarmHandle.setHandleStatus("1");
        when(alarmMapper.selectExistingIdsByTenant(new Long[]{102L}, 10L))
                .thenReturn(Collections.emptyList());

        try {
            alarmHandleService.updateAlarmHandle(alarmHandle);
            fail("every handle update must enforce the current tenant");
        } catch (CustomException expected) {
            // expected
        }

        verifyNoInteractions(redisService, alarmHandleMapper);
    }

    @Test
    public void saveAlarmHandleCompletesAlarmHandleAndActiveReminderWorkorder() {
        HandleParamDto request = new HandleParamDto();
        request.setAlarmId(200L);
        request.setIdentify("0");
        request.setOpinion("现场处理完成");
        request.setHandlePicture("/upload/handled.jpg");
        LoginUser user = new LoginUser();
        user.setUserid(55L);
        user.setUsername("handler-a");
        when(tokenService.getLoginUser()).thenReturn(user);
        when(alarmMapper.selectProcessableIdsByTenant(new Long[]{200L}, 10L))
                .thenReturn(Collections.singletonList(200L));
        when(alarmMapper.handleActiveByIdsAndTenant(eq(new Long[]{200L}), eq(10L), eq("2"),
                eq("handler-a"), any(), eq(null))).thenReturn(1);
        when(alarmHandleMapper.updateAlarmHandle(any(AlarmHandle.class))).thenReturn(1);
        when(alarmWorkorderMapper.completeActiveByAlarmIds(eq(new Long[]{200L}), eq(10L),
                eq("现场处理完成"), eq("handler-a"), any())).thenReturn(1);

        assertEquals(1, alarmHandleService.saveAlarmHandle(request));

        verify(alarmWorkorderMapper).completeActiveByAlarmIds(eq(new Long[]{200L}), eq(10L),
                eq("现场处理完成"), eq("handler-a"), any());
    }

    @Test(expected = CustomException.class)
    public void saveAlarmHandleRequiresPictureAndOpinion() {
        HandleParamDto request = new HandleParamDto();
        request.setAlarmId(200L);
        request.setOpinion("现场处理完成");
        request.setHandlePicture(" ");

        alarmHandleService.saveAlarmHandle(request);
    }

    @Test
    public void saveAlarmAllHandleUsesSameClosedLoop() {
        HandleParamDto request = new HandleParamDto();
        request.setAlarmId(201L);
        request.setSceneType("1");
        request.setIdentify("0");
        request.setOpinion("批量处理完成");
        request.setHandlePicture("/upload/batch.jpg");
        LoginUser user = new LoginUser();
        user.setUserid(56L);
        user.setUsername("handler-b");
        when(tokenService.getLoginUser()).thenReturn(user);
        when(alarmElectrolyticCellMapper.selectAlarmAlarmDetailEcList(any()))
                .thenReturn(Collections.emptyList());
        when(alarmMapper.selectProcessableIdsByTenant(new Long[]{201L}, 10L))
                .thenReturn(Collections.singletonList(201L));
        when(alarmMapper.handleActiveByIdsAndTenant(eq(new Long[]{201L}), eq(10L), eq("2"),
                eq("handler-b"), any(), any())).thenReturn(1);
        when(alarmHandleMapper.updateAlarmHandle(any(AlarmHandle.class))).thenReturn(1);
        when(alarmWorkorderMapper.completeActiveByAlarmIds(eq(new Long[]{201L}), eq(10L),
                eq("批量处理完成"), eq("handler-b"), any())).thenReturn(1);

        assertEquals(1, alarmHandleService.saveAlarmAllHandle(request));

        verify(alarmWorkorderMapper).completeActiveByAlarmIds(eq(new Long[]{201L}), eq(10L),
                eq("批量处理完成"), eq("handler-b"), any());
    }

    private static final class TestAlarmHandleServiceImpl extends AlarmHandleServiceImpl {
        @Override
        protected Long currentTenantId() {
            return 10L;
        }
    }
}
