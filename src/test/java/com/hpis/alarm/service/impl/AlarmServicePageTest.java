package com.hpis.alarm.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.date.DateUtil;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.common.core.exception.CustomException;
import com.hpis.common.redis.service.RedisService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmServicePageTest {

    private static final Date FIXED_NOW = new Date(1784383200000L);

    @Mock
    private AlarmMapper alarmMapper;

    @Mock
    private RedisService redisService;

    private TestAlarmService service;

    @Before
    public void setUp() {
        service = new TestAlarmService(990010L);
        ReflectionTestUtils.setField(service, "alarmMapper", alarmMapper);
        ReflectionTestUtils.setField(service, "baseMapper", alarmMapper);
        ReflectionTestUtils.setField(service, "redisService", redisService);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void selectAlarmPageUsesCurrentTenantAndExcludesDeletedRows() {
        Alarm request = new Alarm();
        request.setTenantId(123L);
        request.setPageNum(2);
        request.setPageSize(15);
        Page<Alarm> emptyPage = new Page<>(2, 15);
        emptyPage.setRecords(Collections.emptyList());
        when(alarmMapper.selectAlarmListPage(any(Page.class), any(Wrapper.class))).thenReturn(emptyPage);

        Page<Alarm> result = service.selectAlarmPage(request);

        assertEquals(emptyPage, result);
        assertEquals(Long.valueOf(990010L), request.getTenantId());

        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(alarmMapper).selectAlarmListPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertEquals(2L, pageCaptor.getValue().getCurrent());
        assertEquals(15L, pageCaptor.getValue().getSize());

        QueryWrapper<?> wrapper = (QueryWrapper<?>) wrapperCaptor.getValue();
        String sql = wrapper.getSqlSegment();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(sql.contains("a.tenant_id"));
        assertTrue(sql.contains("a.del_flag"));
        assertTrue(values.containsValue(990010L));
        assertTrue(values.containsValue("0"));
    }

    @Test
    public void selectAlarmPageRejectsMissingCurrentTenant() {
        service = new TestAlarmService(null);
        ReflectionTestUtils.setField(service, "alarmMapper", alarmMapper);
        ReflectionTestUtils.setField(service, "baseMapper", alarmMapper);
        ReflectionTestUtils.setField(service, "redisService", redisService);

        Alarm request = new Alarm();
        request.setPageNum(1);
        request.setPageSize(10);
        try {
            service.selectAlarmPage(request);
            fail("missing tenant must be rejected");
        } catch (CustomException ex) {
            assertEquals("当前租户不能为空", ex.getMessage());
        }

        verify(alarmMapper, never()).selectAlarmListPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void selectAlarmPageDefaultsToLastThirtyDaysWhenBothTimesAreMissing() {
        Alarm request = emptyRequest();
        stubEmptyPage();

        service.selectAlarmPage(request);

        QueryWrapper<?> wrapper = captureWrapper();
        Date expectedStart = DateUtil.offsetDay(FIXED_NOW, -30);
        String sql = wrapper.getSqlSegment();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(sql.contains("a.alarm_beginTime >="));
        assertTrue(sql.contains("a.alarm_beginTime <="));
        assertTrue(values.containsValue(expectedStart));
        assertTrue(values.containsValue(FIXED_NOW));
    }

    @Test
    public void selectAlarmPageKeepsSuppliedStartTimeWithoutDefaultEndTime() {
        Date suppliedStart = new Date(1770000000000L);
        Alarm request = emptyRequest();
        request.setStartTime(suppliedStart);
        stubEmptyPage();

        service.selectAlarmPage(request);

        QueryWrapper<?> wrapper = captureWrapper();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(wrapper.getSqlSegment().contains("a.alarm_beginTime >"));
        assertTrue(values.containsValue(suppliedStart));
        assertFalse(values.containsValue(FIXED_NOW));
        assertFalse(values.containsValue(DateUtil.offsetDay(FIXED_NOW, -30)));
    }

    @Test
    public void selectAlarmPageKeepsSuppliedEndTimeWithoutDefaultStartTime() {
        Date suppliedEnd = new Date(1771000000000L);
        Alarm request = emptyRequest();
        request.setEndTime(suppliedEnd);
        stubEmptyPage();

        service.selectAlarmPage(request);

        QueryWrapper<?> wrapper = captureWrapper();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(wrapper.getSqlSegment().contains("a.alarm_beginTime <"));
        assertTrue(values.containsValue(suppliedEnd));
        assertFalse(values.containsValue(FIXED_NOW));
        assertFalse(values.containsValue(DateUtil.offsetDay(FIXED_NOW, -30)));
    }

    @Test
    public void selectAlarmPageKeepsBothSuppliedTimes() {
        Date suppliedStart = new Date(1770000000000L);
        Date suppliedEnd = new Date(1771000000000L);
        Alarm request = emptyRequest();
        request.setStartTime(suppliedStart);
        request.setEndTime(suppliedEnd);
        stubEmptyPage();

        service.selectAlarmPage(request);

        QueryWrapper<?> wrapper = captureWrapper();
        wrapper.getSqlSegment();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(values.containsValue(suppliedStart));
        assertTrue(values.containsValue(suppliedEnd));
        assertFalse(values.containsValue(FIXED_NOW));
        assertFalse(values.containsValue(DateUtil.offsetDay(FIXED_NOW, -30)));
    }

    private Alarm emptyRequest() {
        Alarm request = new Alarm();
        request.setPageNum(1);
        request.setPageSize(10);
        return request;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubEmptyPage() {
        Page<Alarm> emptyPage = new Page<>(1, 10);
        emptyPage.setRecords(Collections.emptyList());
        when(alarmMapper.selectAlarmListPage(any(Page.class), any(Wrapper.class))).thenReturn(emptyPage);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private QueryWrapper<?> captureWrapper() {
        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(alarmMapper).selectAlarmListPage(any(Page.class), wrapperCaptor.capture());
        return (QueryWrapper<?>) wrapperCaptor.getValue();
    }

    private static final class TestAlarmService extends AlarmServiceImpl {
        private final Long tenantId;

        private TestAlarmService(Long tenantId) {
            this.tenantId = tenantId;
        }

        protected Long currentTenantId() {
            if (tenantId == null) {
                throw new CustomException("当前租户不能为空");
            }
            return tenantId;
        }

        protected Date currentTime() {
            return FIXED_NOW;
        }
    }
}
