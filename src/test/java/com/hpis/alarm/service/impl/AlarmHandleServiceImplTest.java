package com.hpis.alarm.service.impl;

import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.common.core.exception.CustomException;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmHandleServiceImplTest {

    @Mock
    private AlarmHandleMapper alarmHandleMapper;

    @Mock
    private TokenService tokenService;

    @Mock
    private RedisService redisService;

    private AlarmHandleServiceImpl alarmHandleService;

    @Before
    public void setUp() {
        alarmHandleService = new AlarmHandleServiceImpl();
        ReflectionTestUtils.setField(alarmHandleService, "alarmHandleMapper", alarmHandleMapper);
        ReflectionTestUtils.setField(alarmHandleService, "tokenService", tokenService);
        ReflectionTestUtils.setField(alarmHandleService, "redisService", redisService);
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
}
