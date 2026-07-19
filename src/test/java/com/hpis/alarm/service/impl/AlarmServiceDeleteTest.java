package com.hpis.alarm.service.impl;

import com.hpis.alarm.config.sharding.AlarmCidIndexService;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.service.IAlarmElectrolyticCellService;
import com.hpis.alarm.service.IAlarmPartialDischargeService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.Invocation;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class AlarmServiceDeleteTest {

    private AlarmMapper alarmMapper;

    @Mock
    private AlarmHandleMapper alarmHandleMapper;

    @Mock
    private IAlarmElectrolyticCellService alarmElectrolyticCellService;

    @Mock
    private IAlarmPartialDischargeService alarmPartialDischargeService;

    @Mock
    private AlarmCidIndexService alarmCidIndexService;

    private AlarmServiceImpl alarmService;
    private List<Long> allowedIds;

    @Before
    public void setUp() {
        allowedIds = Arrays.asList(101L, 102L);
        alarmMapper = mock(AlarmMapper.class, invocation -> {
            if ("selectExistingIdsByTenant".equals(invocation.getMethod().getName())) {
                return allowedIds;
            }
            if ("deleteAlarmByIds".equals(invocation.getMethod().getName())) {
                Long[] ids = (Long[]) invocation.getArguments()[0];
                return ids.length;
            }
            return RETURNS_DEFAULTS.answer(invocation);
        });
        alarmService = new TestAlarmServiceImpl(10L);
        ReflectionTestUtils.setField(alarmService, "alarmMapper", alarmMapper);
        ReflectionTestUtils.setField(alarmService, "alarmHandleMapper", alarmHandleMapper);
        ReflectionTestUtils.setField(alarmService, "iAlarmElectrolyticCellService", alarmElectrolyticCellService);
        ReflectionTestUtils.setField(alarmService, "iAlarmPartialDischargeService", alarmPartialDischargeService);
        ReflectionTestUtils.setField(alarmService, "alarmCidIndexService", alarmCidIndexService);
    }

    @Test
    public void deleteAlarmByIdsScopesBusinessTablesAndRoutesToCurrentTenant() {
        Long[] requestedIds = new Long[]{101L, 999L};
        allowedIds = Collections.singletonList(101L);

        int result = alarmService.deleteAlarmByIds(requestedIds);

        assertEquals(1, result);
        Invocation selectInvocation = findInvocation("selectExistingIdsByTenant");
        assertNotNull(selectInvocation);
        assertEquals(10L, selectInvocation.getArguments()[1]);
        verify(alarmMapper).deleteAlarmByIds(argThat(ids -> Arrays.equals(ids, new Long[]{101L})));
        verify(alarmHandleMapper).deleteAlarmHandleByAlarmIds(argThat(ids -> Arrays.equals(ids, new Long[]{101L})));
        verify(alarmElectrolyticCellService).deleteAlarmElectrolyticCellByIds(argThat(ids -> Arrays.equals(ids, new Long[]{101L})));
        verify(alarmPartialDischargeService).deleteAlarmPartialDischargeByIds(argThat(ids -> Arrays.equals(ids, new Long[]{101L})));
        verify(alarmCidIndexService).deleteRoutesByAlarmIds(Collections.singletonList(101L));
    }

    @Test
    public void deleteAlarmByIdReusesBatchDeleteSemantics() {
        allowedIds = Collections.singletonList(201L);

        int result = alarmService.deleteAlarmById(201L);

        assertEquals(1, result);
    }

    @Test
    public void deleteAlarmByIdsReturnsZeroForEmptyInput() {
        int result = alarmService.deleteAlarmByIds(new Long[0]);

        assertEquals(0, result);
        verifyNoInteractions(alarmMapper, alarmHandleMapper, alarmElectrolyticCellService,
                alarmPartialDischargeService, alarmCidIndexService);
    }

    @Test
    public void deleteAlarmByIdsReturnsZeroWhenTenantOwnsNone() {
        allowedIds = Collections.emptyList();

        int result = alarmService.deleteAlarmByIds(new Long[]{999L});

        assertEquals(0, result);
        assertNotNull(findInvocation("selectExistingIdsByTenant"));
        verify(alarmMapper, never()).deleteAlarmByIds(argThat(ids -> true));
        verifyNoInteractions(alarmHandleMapper, alarmElectrolyticCellService,
                alarmPartialDischargeService, alarmCidIndexService);
    }

    @Test
    public void childDeleteFailurePropagatesUnderRollbackTransaction() throws Exception {
        allowedIds = Collections.singletonList(301L);
        IllegalStateException failure = new IllegalStateException("controlled child delete failure");
        doThrow(failure).when(alarmHandleMapper)
                .deleteAlarmHandleByAlarmIds(argThat(ids -> Arrays.equals(ids, new Long[]{301L})));

        try {
            alarmService.deleteAlarmByIds(new Long[]{301L});
            fail("child delete failure must propagate to the transaction boundary");
        } catch (IllegalStateException ex) {
            assertEquals(failure, ex);
        }

        verify(alarmMapper).deleteAlarmByIds(argThat(ids -> Arrays.equals(ids, new Long[]{301L})));
        verifyNoInteractions(alarmElectrolyticCellService, alarmPartialDischargeService, alarmCidIndexService);
        Method method = AlarmServiceImpl.class.getMethod("deleteAlarmByIds", Long[].class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertTrue(Arrays.asList(transactional.rollbackFor()).contains(Exception.class));
    }

    private Invocation findInvocation(String methodName) {
        Collection<Invocation> invocations = mockingDetails(alarmMapper).getInvocations();
        for (Invocation invocation : invocations) {
            if (methodName.equals(invocation.getMethod().getName())) {
                return invocation;
            }
        }
        return null;
    }

    private static final class TestAlarmServiceImpl extends AlarmServiceImpl {
        private final Long tenantId;

        private TestAlarmServiceImpl(Long tenantId) {
            this.tenantId = tenantId;
        }

        @Override
        protected Long currentTenantId() {
            return tenantId;
        }
    }
}
