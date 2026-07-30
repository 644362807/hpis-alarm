package com.hpis.alarm.service.impl;

import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.dto.AlarmQueryParameter;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.service.IAlarmElectrolyticCellService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmServiceStatisticsTimeTest {

    private static final Date FIXED_NOW = new Date(1785427200123L);

    @Mock
    private AlarmMapper alarmMapper;

    @Mock
    private IAlarmElectrolyticCellService electrolyticCellService;

    private TestAlarmService service;

    @Before
    public void setUp() {
        service = new TestAlarmService();
        ReflectionTestUtils.setField(service, "alarmMapper", alarmMapper);
        ReflectionTestUtils.setField(service, "iAlarmElectrolyticCellService", electrolyticCellService);
    }

    @Test
    public void alarmModeCountUsesSameCurrentEndAndDoesNotMutateRequest() {
        AlarmQueryParameter request = new AlarmQueryParameter();
        when(alarmMapper.countAlarmMode(any(AlarmQueryParameter.class)))
                .thenReturn(Collections.singletonList(modeRow()));
        when(alarmMapper.countNoHandelOfDay(any(AlarmQueryParameter.class)))
                .thenReturn(Collections.singletonList(handleRow()));

        Map<String, Long> result = service.alarmModeCount(request);

        ArgumentCaptor<AlarmQueryParameter> modeCaptor = ArgumentCaptor.forClass(AlarmQueryParameter.class);
        ArgumentCaptor<AlarmQueryParameter> handleCaptor = ArgumentCaptor.forClass(AlarmQueryParameter.class);
        verify(alarmMapper).countAlarmMode(modeCaptor.capture());
        verify(alarmMapper).countNoHandelOfDay(handleCaptor.capture());
        assertEquals(FIXED_NOW, modeCaptor.getValue().getEndTime());
        assertEquals(FIXED_NOW, handleCaptor.getValue().getEndTime());
        assertNotSame(request, modeCaptor.getValue());
        assertNull(request.getEndTime());
        assertNull(request.getAlarmStatus());
        assertEquals(Long.valueOf(1L), result.get("level1"));
    }

    @Test
    public void alarmCountByTimeClampsFutureEndAndSupportsMissingStart() {
        AlarmQueryParameter request = new AlarmQueryParameter();
        request.setEndTime(new Date(FIXED_NOW.getTime() + 1000L));
        Map<String, Long> countRow = new HashMap<>();
        countRow.put("count_today", 2L);
        countRow.put("total_count_custom_range", 9L);
        when(alarmMapper.alarmCountByTime(any(AlarmQueryParameter.class)))
                .thenReturn(Collections.singletonList(countRow));
        when(electrolyticCellService.selectAlarmListByEC(any(AlarmQueryParameter.class)))
                .thenReturn(Collections.emptyList());

        Map<String, Long> result = service.alarmCountByTime(request);

        ArgumentCaptor<AlarmQueryParameter> captor = ArgumentCaptor.forClass(AlarmQueryParameter.class);
        verify(alarmMapper).alarmCountByTime(captor.capture());
        ArgumentCaptor<AlarmQueryParameter> currentAlarmCaptor =
                ArgumentCaptor.forClass(AlarmQueryParameter.class);
        verify(electrolyticCellService).selectAlarmListByEC(currentAlarmCaptor.capture());
        assertNull(captor.getValue().getStartTime());
        assertEquals(FIXED_NOW, captor.getValue().getEndTime());
        assertEquals(FIXED_NOW, currentAlarmCaptor.getValue().getEndTime());
        assertEquals(new Date(FIXED_NOW.getTime() + 1000L), request.getEndTime());
        assertEquals(Long.valueOf(9L), result.get("allAlarm"));
        assertTrue(result.containsKey("currentAlarmCount"));
    }

    @Test
    public void alarmOfDayAddsCurrentEndWithoutMutatingRequest() {
        AlarmQueryParameter request = new AlarmQueryParameter();
        when(alarmMapper.alarmOfDay(any(AlarmQueryParameter.class)))
                .thenReturn(Collections.emptyList());

        service.AlarmOfDay(request);

        ArgumentCaptor<AlarmQueryParameter> captor = ArgumentCaptor.forClass(AlarmQueryParameter.class);
        verify(alarmMapper).alarmOfDay(captor.capture());
        assertEquals(FIXED_NOW, captor.getValue().getEndTime());
        assertNull(request.getEndTime());
    }

    @Test
    public void alarmTimeCountByMonthEndsAtCurrentTimeAndDoesNotMutateRequest() {
        AlarmQueryParameter request = new AlarmQueryParameter();
        when(alarmMapper.selectAlarmByQueryParameter(any(AlarmQueryParameter.class)))
                .thenReturn(new ArrayList<Alarm>());

        service.alarmTimeCountByMonth(request);

        ArgumentCaptor<AlarmQueryParameter> captor = ArgumentCaptor.forClass(AlarmQueryParameter.class);
        verify(alarmMapper).selectAlarmByQueryParameter(captor.capture());
        assertEquals(FIXED_NOW, captor.getValue().getEndTime());
        assertTrue(captor.getValue().getStartTime().before(FIXED_NOW));
        assertNull(request.getStartTime());
        assertNull(request.getEndTime());
    }

    private Map<String, Long> modeRow() {
        Map<String, Long> row = new HashMap<>();
        row.put("rank0", 1L);
        row.put("rank1", 2L);
        row.put("rank2", 3L);
        return row;
    }

    private Map<String, Long> handleRow() {
        Map<String, Long> row = new HashMap<>();
        row.put("count_of_transactions_today", 4L);
        row.put("count_of_transactions_three_to_seven_days_ago", 5L);
        row.put("count_of_transactions_before_seven_days_ago", 6L);
        return row;
    }

    private static final class TestAlarmService extends AlarmServiceImpl {
        @Override
        protected Date currentTime() {
            return FIXED_NOW;
        }
    }
}
