package com.hpis.alarm.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.common.core.exception.CustomException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmServiceCountTest {

    private static final Date FIXED_NOW = new Date(1785427200123L);

    @Mock
    private AlarmMapper alarmMapper;

    private TestAlarmService service;

    @Before
    public void setUp() {
        service = new TestAlarmService(71L);
        ReflectionTestUtils.setField(service, "alarmMapper", alarmMapper);
        when(alarmMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
    }

    @Test
    public void countAlarmAddsTenantAndCurrentEndWhenTimeIsMissing() {
        service.countAlarm(new Alarm());

        QueryWrapper<?> wrapper = captureWrapper();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(wrapper.getSqlSegment().contains("tenant_id"));
        assertTrue(wrapper.getSqlSegment().contains("alarm_beginTime <"));
        assertTrue(values.containsValue(71L));
        assertTrue(values.containsValue(FIXED_NOW));
    }

    @Test
    public void countAlarmCompletesStartOnlyRange() {
        Date startTime = new Date(FIXED_NOW.getTime() - 1000L);
        Alarm alarm = new Alarm();
        alarm.setStartTime(startTime);

        service.countAlarm(alarm);

        QueryWrapper<?> wrapper = captureWrapper();
        wrapper.getSqlSegment();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(values.toString(), containsDate(values, startTime));
        assertTrue(values.toString(), containsDate(values, FIXED_NOW));
    }

    @Test
    public void countAlarmPreservesHistoricalEnd() {
        Date endTime = new Date(FIXED_NOW.getTime() - 1000L);
        Alarm alarm = new Alarm();
        alarm.setEndTime(endTime);

        service.countAlarm(alarm);

        QueryWrapper<?> wrapper = captureWrapper();
        wrapper.getSqlSegment();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(values.toString(), containsDate(values, endTime));
        assertFalse(values.toString(), containsDate(values, FIXED_NOW));
    }

    @Test
    public void countAlarmClampsFutureEnd() {
        Alarm alarm = new Alarm();
        alarm.setEndTime(new Date(FIXED_NOW.getTime() + 1000L));

        service.countAlarm(alarm);

        QueryWrapper<?> wrapper = captureWrapper();
        wrapper.getSqlSegment();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(values.toString(), containsDate(values, FIXED_NOW));
    }

    @Test
    public void countAlarmRejectsMissingTenantBeforeMapperCall() {
        service = new TestAlarmService(null);
        ReflectionTestUtils.setField(service, "alarmMapper", alarmMapper);

        try {
            service.countAlarm(new Alarm());
            fail("missing tenant must be rejected");
        } catch (CustomException expected) {
            // expected
        }

        verify(alarmMapper, never()).selectCount(any(Wrapper.class));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private QueryWrapper<?> captureWrapper() {
        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(alarmMapper).selectCount(captor.capture());
        return (QueryWrapper<?>) captor.getValue();
    }

    private boolean containsDate(Map<String, Object> values, Date expected) {
        for (Object value : values.values()) {
            if (value instanceof Date && ((Date) value).getTime() == expected.getTime()) {
                return true;
            }
        }
        return false;
    }

    private static final class TestAlarmService extends AlarmServiceImpl {
        private final Long tenantId;

        private TestAlarmService(Long tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        protected Long currentTenantId() {
            if (tenantId == null) {
                throw new CustomException("current tenant is required");
            }
            return tenantId;
        }

        @Override
        protected Date currentTime() {
            return FIXED_NOW;
        }
    }
}
