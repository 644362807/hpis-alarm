package com.hpis.alarm.config.sharding;

import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.mapper.AlarmCidIndexMapper;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Date;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmCidIndexServiceTest {

    @Mock
    private AlarmMonthlySliceTableManager tableManager;

    @Mock
    private AlarmCidIndexMapper cidIndexMapper;

    @Mock
    private AlarmIdCodec alarmIdCodec;

    private AlarmCidIndexService service;

    @Before
    public void setUp() {
        AlarmShardProperties properties = new AlarmShardProperties();
        properties.getCidIndex().setTransferBatchSize(10);
        properties.getCidIndex().setStaleExpireDays(30);
        service = new AlarmCidIndexService(tableManager, cidIndexMapper, alarmIdCodec, properties);
    }

    @Test
    public void transferHotToStaleWritesStaleBeforeDeletingHot() {
        AlarmCidRoute route = new AlarmCidRoute();
        route.setAlarmId(1001L);
        route.setAlarmCid("cid-1001");
        route.setRouteStatus(AlarmCidRoute.STATUS_ACTIVE);
        when(cidIndexMapper.selectHotTransferCandidates(any(Date.class), eq(10)))
                .thenReturn(Collections.singletonList(route));
        when(cidIndexMapper.deleteHotActiveByAlarmId(1001L)).thenReturn(1);

        service.transferHotToStale();

        verify(cidIndexMapper).upsertStaleActive(route);
        verify(cidIndexMapper).deleteHotActiveByAlarmId(1001L);
    }

    @Test
    public void closeRouteUpdatesTheSourceIndexTable() {
        AlarmCidRoute route = new AlarmCidRoute();
        route.setAlarmId(1002L);
        route.setIndexSource(AlarmCidRoute.SOURCE_STALE);
        Date endTime = new Date();

        service.closeRoute(route, endTime);

        verify(cidIndexMapper).closeStaleByAlarmId(eq(1002L), eq(endTime), any(Date.class));
    }
}
