package com.hpis.alarm.task;

import com.hpis.alarm.config.sharding.AlarmShardingRuleRefreshService;
import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class AlarmShardingRuleRefreshJobTest {

    @Test
    public void shouldDelegateScheduledRefreshToSharedService() {
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        AlarmShardingRuleRefreshJob job = new AlarmShardingRuleRefreshJob(refreshService);

        job.refreshRule();

        verify(refreshService).refreshNow("monthly-scheduled");
    }
}
