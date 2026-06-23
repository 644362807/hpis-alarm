package com.hpis.alarm.service.impl;

import com.hpis.alarm.config.sharding.AlarmCidIndexService;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.service.IAlarmElectrolyticCellService;
import com.hpis.alarm.service.IAlarmPartialDischargeService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmServiceDeleteTest {

    @Mock
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

    @Before
    public void setUp() {
        alarmService = new AlarmServiceImpl();
        ReflectionTestUtils.setField(alarmService, "alarmMapper", alarmMapper);
        ReflectionTestUtils.setField(alarmService, "alarmHandleMapper", alarmHandleMapper);
        ReflectionTestUtils.setField(alarmService, "iAlarmElectrolyticCellService", alarmElectrolyticCellService);
        ReflectionTestUtils.setField(alarmService, "iAlarmPartialDischargeService", alarmPartialDischargeService);
        ReflectionTestUtils.setField(alarmService, "alarmCidIndexService", alarmCidIndexService);
    }

    @Test
    public void deleteAlarmByIdsSoftDeletesBusinessTablesAndHardDeletesRoutes() {
        Long[] alarmIds = new Long[]{101L, 102L};
        when(alarmMapper.deleteAlarmByIds(alarmIds)).thenReturn(2);

        int result = alarmService.deleteAlarmByIds(alarmIds);

        assertEquals(2, result);
        InOrder order = inOrder(alarmMapper, alarmHandleMapper, alarmElectrolyticCellService,
                alarmPartialDischargeService, alarmCidIndexService);
        order.verify(alarmMapper).deleteAlarmByIds(alarmIds);
        order.verify(alarmHandleMapper).deleteAlarmHandleByAlarmIds(alarmIds);
        order.verify(alarmElectrolyticCellService).deleteAlarmElectrolyticCellByIds(alarmIds);
        order.verify(alarmPartialDischargeService).deleteAlarmPartialDischargeByIds(alarmIds);
        order.verify(alarmCidIndexService).deleteRoutesByAlarmIds(Arrays.asList(alarmIds));
    }

    @Test
    public void deleteAlarmByIdReusesBatchDeleteSemantics() {
        when(alarmMapper.deleteAlarmByIds(any(Long[].class))).thenReturn(1);

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
}
