package com.hpis.alarm.task;

import com.hpis.alarm.config.sharding.AlarmMonthlySliceTableManager;
import com.hpis.alarm.config.sharding.AlarmShardProperties;
import com.hpis.alarm.config.sharding.AlarmShardingRuleRefreshService;
import org.junit.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlarmShardingNextMonthPreCreateJobTest {

    @Test
    public void shouldSkipWhenTodayIsNotMonthEnd() {
        AlarmMonthlySliceTableManager tableManager = mock(AlarmMonthlySliceTableManager.class);
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        AlarmShardProperties properties = new AlarmShardProperties();
        AlarmShardingNextMonthPreCreateJob job =
                new AlarmShardingNextMonthPreCreateJob(tableManager, refreshService, properties);

        job.preCreateNextMonth(LocalDate.of(2026, 6, 29));

        verify(tableManager, times(0)).preCreateNextMonthSlices(LocalDate.of(2026, 6, 29), 0);
        verify(refreshService, times(0)).refreshNow("month-end-precreate");
    }

    @Test
    public void shouldPreCreateAndRefreshWhenTodayIsMonthEnd() {
        AlarmMonthlySliceTableManager tableManager = mock(AlarmMonthlySliceTableManager.class);
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        AlarmShardProperties properties = new AlarmShardProperties();
        when(tableManager.preCreateNextMonthSlices(LocalDate.of(2026, 6, 30), 0)).thenReturn(true);
        AlarmShardingNextMonthPreCreateJob job =
                new AlarmShardingNextMonthPreCreateJob(tableManager, refreshService, properties);

        job.preCreateNextMonth(LocalDate.of(2026, 6, 30));

        verify(tableManager).preCreateNextMonthSlices(LocalDate.of(2026, 6, 30), 0);
        verify(refreshService).refreshNow("month-end-precreate");
    }

    @Test
    public void shouldUseConfiguredPhysicalSliceRange() {
        AlarmMonthlySliceTableManager tableManager = mock(AlarmMonthlySliceTableManager.class);
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        AlarmShardProperties properties = new AlarmShardProperties();
        properties.getRuleRefresh().setMonthEndPreCreateNextMonthMaxSliceNo(3);
        AlarmShardingNextMonthPreCreateJob job =
                new AlarmShardingNextMonthPreCreateJob(tableManager, refreshService, properties);

        job.preCreateNextMonth(LocalDate.of(2026, 7, 31));

        verify(tableManager).preCreateNextMonthSlices(LocalDate.of(2026, 7, 31), 3);
        verify(refreshService).refreshNow("month-end-precreate");
    }

    @Test
    public void shouldNotRefreshWhenPreCreateFails() {
        AlarmMonthlySliceTableManager tableManager = mock(AlarmMonthlySliceTableManager.class);
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        AlarmShardProperties properties = new AlarmShardProperties();
        doThrow(new IllegalStateException("ddl failed"))
                .when(tableManager).preCreateNextMonthSlices(LocalDate.of(2026, 8, 31), 0);
        AlarmShardingNextMonthPreCreateJob job =
                new AlarmShardingNextMonthPreCreateJob(tableManager, refreshService, properties);

        job.preCreateNextMonth(LocalDate.of(2026, 8, 31));

        verify(refreshService, times(0)).refreshNow("month-end-precreate");
    }

    @Test
    public void shouldIdentifyLastDayOfMonth() {
        AlarmShardingNextMonthPreCreateJob job =
                new AlarmShardingNextMonthPreCreateJob(null, null, new AlarmShardProperties());

        assertThat(job.isLastDayOfMonth(LocalDate.of(2026, 2, 28))).isTrue();
        assertThat(job.isLastDayOfMonth(LocalDate.of(2026, 2, 27))).isFalse();
    }
}
