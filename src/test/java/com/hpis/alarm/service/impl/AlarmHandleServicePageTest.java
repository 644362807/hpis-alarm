package com.hpis.alarm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.common.core.exception.CustomException;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.Date;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

public class AlarmHandleServicePageTest {

    private AlarmHandleMapper alarmHandleMapper;
    private Page<AlarmHandle> expectedPage;
    private TestAlarmHandleService service;

    @Before
    public void setUp() {
        expectedPage = new Page<>(3, 20);
        alarmHandleMapper = mock(AlarmHandleMapper.class, invocation -> {
            if ("selectAlarmHandlePage".equals(invocation.getMethod().getName())) {
                return expectedPage;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        service = new TestAlarmHandleService(990010L);
        ReflectionTestUtils.setField(service, "alarmHandleMapper", alarmHandleMapper);
        ReflectionTestUtils.setField(service, "baseMapper", alarmHandleMapper);
    }

    @Test
    public void selectAlarmHandlePageUsesTenantAlarmAndExistingFilters() {
        AlarmHandle request = new AlarmHandle();
        request.setAlarmId(672725194338658841L);
        request.setAlarmType("10");
        request.setAlarmRank("1");
        request.setAlarmStatus("0");
        request.setAlarmBegintime(new Date(1000L));
        request.setAlarmEndtime(new Date(2000L));
        request.setPageNum(3);
        request.setPageSize(20);

        Page<AlarmHandle> result = service.selectAlarmHandlePage(request);

        assertEquals(expectedPage, result);
        Invocation invocation = findInvocation("selectAlarmHandlePage");
        assertNotNull(invocation);
        Page<?> page = (Page<?>) invocation.getArguments()[0];
        QueryWrapper<?> wrapper = (QueryWrapper<?>) invocation.getArguments()[1];
        assertEquals(3L, page.getCurrent());
        assertEquals(20L, page.getSize());
        String sql = wrapper.getSqlSegment();
        Map<String, Object> values = wrapper.getParamNameValuePairs();
        assertTrue(sql.contains("a.tenant_id"));
        assertTrue(sql.contains("a.del_flag"));
        assertTrue(sql.contains("a.alarm_id"));
        assertTrue(sql.contains("a.alarm_type"));
        assertTrue(sql.contains("a.alarm_rank"));
        assertTrue(sql.contains("a.alarm_status"));
        assertTrue(sql.contains("a.alarm_beginTime"));
        assertTrue(values.containsValue(990010L));
        assertTrue(values.containsValue("0"));
        assertTrue(values.containsValue(672725194338658841L));
    }

    @Test
    public void selectAlarmHandlePageRejectsMissingTenantBeforeMapperCall() {
        service = new TestAlarmHandleService(null);
        ReflectionTestUtils.setField(service, "alarmHandleMapper", alarmHandleMapper);
        ReflectionTestUtils.setField(service, "baseMapper", alarmHandleMapper);
        AlarmHandle request = new AlarmHandle();
        request.setPageNum(1);
        request.setPageSize(10);

        try {
            service.selectAlarmHandlePage(request);
            fail("missing tenant must be rejected");
        } catch (CustomException ex) {
            assertEquals("当前租户不能为空", ex.getMessage());
        }

        assertTrue(mockingDetails(alarmHandleMapper).getInvocations().isEmpty());
    }

    private Invocation findInvocation(String methodName) {
        Collection<Invocation> invocations = mockingDetails(alarmHandleMapper).getInvocations();
        for (Invocation invocation : invocations) {
            if (methodName.equals(invocation.getMethod().getName())) {
                return invocation;
            }
        }
        return null;
    }

    private static final class TestAlarmHandleService extends AlarmHandleServiceImpl {
        private final Long tenantId;

        private TestAlarmHandleService(Long tenantId) {
            this.tenantId = tenantId;
        }

        protected Long currentTenantId() {
            if (tenantId == null) {
                throw new CustomException("当前租户不能为空");
            }
            return tenantId;
        }
    }
}
