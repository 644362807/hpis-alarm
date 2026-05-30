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
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import static org.junit.Assert.assertEquals;

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
        when(cidIndexMapper.deleteHotActiveByAlarmIds(anyList())).thenReturn(1);

        service.transferHotToStale();

        InOrder order = inOrder(cidIndexMapper);
        order.verify(cidIndexMapper).upsertStaleActiveBatch(Collections.singletonList(route));
        order.verify(cidIndexMapper).deleteHotActiveByAlarmIds(Collections.singletonList(1001L));
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

    @Test
    public void closeRoutesChunksOversizedBatch() {
        List<AlarmCidRoute> routes = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            AlarmCidRoute route = new AlarmCidRoute();
            route.setAlarmId((long) i + 1);
            route.setAlarmCid("cid-" + i);
            route.setIndexSource(AlarmCidRoute.SOURCE_HOT);
            routes.add(route);
        }

        service.closeRoutes(routes, new Date());

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(cidIndexMapper, times(2)).closeHotBatch(captor.capture(), any(Date.class));
        assertEquals(500, captor.getAllValues().get(0).size());
        assertEquals(1, captor.getAllValues().get(1).size());
    }
}
