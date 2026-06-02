package com.hpis.alarm.service;

import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.config.sharding.AlarmCidIndexService;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.domain.AlarmStopEvent;
import com.hpis.alarm.dto.AlarmStopRecord;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.mapper.AlarmStopEventMapper;
import com.hpis.alarm.task.AlarmStopWorkerSignal;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmStopEventServiceTest {

    @Mock
    private AlarmStopEventMapper stopEventMapper;
    @Mock
    private AlarmMapper alarmMapper;
    @Mock
    private AlarmCidIndexService alarmCidIndexService;
    @Mock
    private AlarmStopSideEffectService sideEffectService;
    @Mock
    private AlarmStopWorkerSignal workerSignal;

    private AlarmStopWorkerProperties workerProperties;
    private AlarmBatchProperties batchProperties;
    private AlarmStopEventService service;

    @Before
    public void setUp() {
        workerProperties = new AlarmStopWorkerProperties();
        batchProperties = new AlarmBatchProperties();
        batchProperties.setInLimit(500);
        service = new AlarmStopEventService(stopEventMapper, alarmMapper, alarmCidIndexService,
                sideEffectService, batchProperties, workerProperties, workerSignal);
    }

    @Test
    public void processPendingBatchChunksRouteLookupByConfiguredInLimit() {
        workerProperties.setHighWatermark(1000);
        workerProperties.setHighBatchSize(1200);
        List<AlarmStopEvent> events = buildEvents(1200);
        when(stopEventMapper.countPending()).thenReturn(1200);
        when(stopEventMapper.selectPendingBatch(1200)).thenReturn(events);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), eq(500)))
                .thenReturn(Collections.emptyMap());
        when(alarmCidIndexService.findRoutesByCids(anyList(), eq(500)))
                .thenReturn(Collections.emptyMap());

        int processed = service.processPendingBatch();

        assertEquals(0, processed);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(alarmCidIndexService, org.mockito.Mockito.times(3)).findActiveRoutesByCids(captor.capture(), eq(500));
        assertEquals(500, captor.getAllValues().get(0).size());
        assertEquals(500, captor.getAllValues().get(1).size());
        assertEquals(200, captor.getAllValues().get(2).size());
        verify(stopEventMapper, org.mockito.Mockito.times(3)).markRetryBatch(anyList(), eq("ROUTE_MISSING"));
        verify(alarmCidIndexService, never()).findActiveRouteByCid(any());
    }

    @Test
    public void routeMissingKeepsPendingBeforeMaxRetry() {
        List<AlarmStopEvent> events = buildEvents(2);
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize())).thenReturn(events);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());
        when(alarmCidIndexService.findRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());

        int processed = service.processPendingBatch();

        assertEquals(0, processed);
        verify(stopEventMapper).markRetryBatch(eq(ids(events)), eq("ROUTE_MISSING"));
        verify(stopEventMapper, never()).markFailedBatch(anyList(), eq("ROUTE_MISSING"));
        verify(alarmCidIndexService, never()).findActiveRouteByCid(any());
    }

    @Test
    public void routeMissingMarksFailedAfterMaxRetry() {
        List<AlarmStopEvent> events = buildEvents(2);
        for (AlarmStopEvent event : events) {
            event.setRetryCount(workerProperties.getMaxRetry() - 1);
        }
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize())).thenReturn(events);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());
        when(alarmCidIndexService.findRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());

        int processed = service.processPendingBatch();

        assertEquals(2, processed);
        verify(stopEventMapper).markFailedBatch(eq(ids(events)), eq("ROUTE_MISSING"));
        verify(stopEventMapper, never()).markRetryBatch(anyList(), eq("ROUTE_MISSING"));
    }

    @Test
    public void activeRouteUsesBatchApplyPath() {
        AlarmStopEvent event = buildEvent(1L, "cid-1");
        AlarmCidRoute route = buildRoute("cid-1", 1001L, "202605_00");
        Alarm alarm = new Alarm();
        alarm.setAlarmId(1001L);
        alarm.setAlarmCid("cid-1");
        alarm.setSceneType("1");
        Map<String, AlarmCidRoute> activeRoutes = new LinkedHashMap<>();
        activeRoutes.put("cid-1", route);
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize()))
                .thenReturn(Collections.singletonList(event));
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(activeRoutes);
        when(alarmMapper.selectAlarmByIdsForStop(Collections.singletonList(1001L)))
                .thenReturn(Collections.singletonList(alarm));
        when(alarmMapper.batchStopByAlarmIds(anyList(), any())).thenReturn(1);

        int processed = service.processPendingBatch();

        assertEquals(1, processed);
        verify(alarmMapper).batchStopByAlarmIds(anyList(), any());
        verify(alarmCidIndexService).closeRoutesByItems(anyList(), anyList(), any(Date.class));
        verify(stopEventMapper).markAppliedBatch(anyList(), any(Date.class));
        verify(sideEffectService).createEventsBatch(anyList(), any(Map.class), any());
        verify(alarmCidIndexService, never()).findActiveRouteByCid(any());
    }

    @Test
    public void routeLookupExceptionFallsBackToSinglePath() {
        List<AlarmStopEvent> events = buildEvents(2);
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize())).thenReturn(events);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt()))
                .thenThrow(new RuntimeException("route query failed"));
        when(alarmCidIndexService.findActiveRouteByCid(any())).thenReturn(null);
        when(alarmCidIndexService.findRouteByCid(any())).thenReturn(null);

        int processed = service.processPendingBatch();

        assertEquals(0, processed);
        verify(alarmCidIndexService, org.mockito.Mockito.times(2)).findActiveRouteByCid(any());
        verify(stopEventMapper, org.mockito.Mockito.times(2)).markRetryBatch(anyList(), eq("ROUTE_MISSING"));
    }

    @Test
    public void recordStopsUsesBoundedChunksAndInitialClaimDelay() {
        batchProperties.setStopEventUpsertBatchSize(2);
        workerProperties.setInitialAvailableDelayMs(200L);
        long before = System.currentTimeMillis();
        List<AlarmStopRecord> records = Arrays.asList(
                new AlarmStopRecord("cid-1", new Date()),
                new AlarmStopRecord("cid-2", new Date()),
                new AlarmStopRecord("cid-3", new Date()));

        service.recordStops(records);

        ArgumentCaptor<List> chunks = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Date> availableTimes = ArgumentCaptor.forClass(Date.class);
        verify(stopEventMapper, org.mockito.Mockito.times(2))
                .upsertPendingBatch(chunks.capture(), availableTimes.capture());
        assertEquals(2, chunks.getAllValues().get(0).size());
        assertEquals(1, chunks.getAllValues().get(1).size());
        org.junit.Assert.assertTrue(availableTimes.getAllValues().get(0).getTime() >= before + 150L);
        verify(workerSignal).wakeUp("recordStops", null);
    }

    @Test
    public void claimedActiveRouteUsesTokenConditionalApply() {
        AlarmStopEvent event = buildEvent(1L, "cid-1");
        AlarmCidRoute route = buildRoute("cid-1", 1001L, "202605_00");
        Alarm alarm = new Alarm();
        alarm.setAlarmId(1001L);
        alarm.setAlarmCid("cid-1");
        Map<String, AlarmCidRoute> activeRoutes = new LinkedHashMap<>();
        activeRoutes.put("cid-1", route);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(activeRoutes);
        when(alarmMapper.selectAlarmByIdsForStop(Collections.singletonList(1001L)))
                .thenReturn(Collections.singletonList(alarm));
        when(alarmMapper.batchStopByAlarmIds(anyList(), any())).thenReturn(1);
        when(stopEventMapper.markProcessingAppliedBatch(anyList(), eq("token-1"), any(Date.class))).thenReturn(1);

        int processed = service.processClaimedBatch("token-1", Collections.singletonList(event));

        assertEquals(1, processed);
        verify(stopEventMapper).markProcessingAppliedBatch(anyList(), eq("token-1"), any(Date.class));
        verify(stopEventMapper, never()).markAppliedBatch(anyList(), any(Date.class));
    }

    @Test
    public void claimedApplyRollsBackWhenTokenOrVersionUpdateCountDoesNotMatch() {
        AlarmStopEvent event = buildEvent(1L, "cid-1");
        event.setEventVersion(3L);
        AlarmCidRoute route = buildRoute("cid-1", 1001L, "202605_00");
        Alarm alarm = new Alarm();
        alarm.setAlarmId(1001L);
        alarm.setAlarmCid("cid-1");
        Map<String, AlarmCidRoute> activeRoutes = new LinkedHashMap<>();
        activeRoutes.put("cid-1", route);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(activeRoutes);
        when(alarmMapper.selectAlarmByIdsForStop(Collections.singletonList(1001L)))
                .thenReturn(Collections.singletonList(alarm));
        when(alarmMapper.batchStopByAlarmIds(anyList(), any())).thenReturn(1);
        when(stopEventMapper.markProcessingAppliedBatch(anyList(), eq("token-stale"), any(Date.class))).thenReturn(0);

        try {
            service.processClaimedBatch("token-stale", Collections.singletonList(event));
            fail("stale token/version must roll back the core stop transaction");
        } catch (IllegalStateException expected) {
            org.junit.Assert.assertTrue(expected.getMessage().contains("token/version"));
        }
    }

    @Test
    public void claimedRouteMissingReturnsToDelayedPending() {
        List<AlarmStopEvent> events = buildEvents(2);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());
        when(alarmCidIndexService.findRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());

        int processed = service.processClaimedBatch("token-1", events);

        assertEquals(0, processed);
        verify(stopEventMapper).markProcessingRetryBatch(eq(ids(events)), eq("token-1"),
                eq("ROUTE_MISSING"), any(Date.class));
        verify(stopEventMapper, never()).markRetryBatch(anyList(), eq("ROUTE_MISSING"));
    }

    @Test
    public void routeMissingUsesClampedMaxRetryWhenConfigurationIsTooLarge() {
        workerProperties.setMaxRetry(999);
        List<AlarmStopEvent> events = buildEvents(2);
        for (AlarmStopEvent event : events) {
            event.setRetryCount(workerProperties.safeMaxRetry() - 1);
        }
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize())).thenReturn(events);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());
        when(alarmCidIndexService.findRoutesByCids(anyList(), anyInt())).thenReturn(Collections.emptyMap());

        int processed = service.processPendingBatch();

        assertEquals(2, processed);
        verify(stopEventMapper).markFailedBatch(eq(ids(events)), eq("ROUTE_MISSING"));
        verify(stopEventMapper, never()).markRetryBatch(anyList(), eq("ROUTE_MISSING"));
    }

    @Test
    public void oversizedRouteLookupFailureDoesNotExpandIntoSingleQueries() {
        batchProperties.setFallbackSingleMaxItems(1);
        List<AlarmStopEvent> events = buildEvents(2);
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize())).thenReturn(events);
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt()))
                .thenThrow(new RuntimeException("route query failed"));

        try {
            service.processPendingBatch();
            fail("oversized fallback should keep the batch for worker retry");
        } catch (RuntimeException expected) {
            assertEquals("route query failed", expected.getCause().getMessage());
        }

        verify(alarmCidIndexService, never()).findActiveRouteByCid(any());
    }

    @Test
    public void failedRouteMissingCanRecoverWhenRouteAppears() {
        AlarmStopEvent event = buildEvent(1L, "cid-1");
        event.setEventStatus(AlarmStopEvent.STATUS_FAILED);
        event.setLastError("ROUTE_MISSING");
        AlarmCidRoute route = buildRoute("cid-1", 1001L, "202605_00");
        Alarm alarm = new Alarm();
        alarm.setAlarmId(1001L);
        alarm.setAlarmCid("cid-1");
        Map<String, AlarmCidRoute> activeRoutes = new LinkedHashMap<>();
        activeRoutes.put("cid-1", route);
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize()))
                .thenReturn(Collections.emptyList());
        when(stopEventMapper.selectFailedRouteMissingBatch(eq(workerProperties.getRouteMissingRecoveryBatchSize()), any(Date.class)))
                .thenReturn(Collections.singletonList(event));
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(activeRoutes);
        when(alarmMapper.selectAlarmByIdsForStop(Collections.singletonList(1001L)))
                .thenReturn(Collections.singletonList(alarm));
        when(alarmMapper.batchStopByAlarmIds(anyList(), any())).thenReturn(1);

        int processed = service.processPendingBatch();

        assertEquals(1, processed);
        verify(alarmMapper).batchStopByAlarmIds(anyList(), any());
        verify(alarmCidIndexService).closeRoutesByItems(anyList(), anyList(), any(Date.class));
        verify(stopEventMapper).markAppliedBatch(anyList(), any(Date.class));
    }

    @Test
    public void batchApplyExceptionDoesNotFallbackToSinglePath() {
        AlarmStopEvent event = buildEvent(1L, "cid-1");
        AlarmCidRoute route = buildRoute("cid-1", 1001L, "202605_00");
        Map<String, AlarmCidRoute> activeRoutes = new LinkedHashMap<>();
        activeRoutes.put("cid-1", route);
        when(stopEventMapper.selectPendingBatch(workerProperties.getNormalBatchSize()))
                .thenReturn(Collections.singletonList(event));
        when(alarmCidIndexService.findActiveRoutesByCids(anyList(), anyInt())).thenReturn(activeRoutes);
        when(alarmMapper.batchStopByAlarmIds(anyList(), any()))
                .thenThrow(new RuntimeException("apply failed"));

        try {
            service.processPendingBatch();
            fail("batch apply failure should be retried by worker, not replayed through single fallback");
        } catch (RuntimeException expected) {
            assertEquals("apply failed", expected.getMessage());
        }

        verify(alarmCidIndexService, never()).findActiveRouteByCid(any());
    }

    @Test
    public void pendingStopCompensationPropagatesOutboxFailure() {
        AlarmStopEvent event = buildEvent(1L, "cid-1");
        AlarmCidRoute route = buildRoute("cid-1", 1001L, "202605_00");
        Alarm alarm = new Alarm();
        alarm.setAlarmId(1001L);
        alarm.setAlarmCid("cid-1");
        when(stopEventMapper.selectRecoverableByCid("cid-1")).thenReturn(event);
        doThrow(new RuntimeException("outbox unavailable"))
                .when(sideEffectService).createEvents(alarm, route);

        try {
            service.applyPendingStopForNewAlarm(alarm, route);
            fail("outbox failure must roll back pending-stop compensation");
        } catch (RuntimeException expected) {
            assertEquals("outbox unavailable", expected.getMessage());
        }

        verify(alarmCidIndexService).closeRoute(eq(route), any(Date.class));
        verify(stopEventMapper, never()).markApplied(any(), any(Date.class));
    }

    private List<AlarmStopEvent> buildEvents(int size) {
        List<AlarmStopEvent> events = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            events.add(buildEvent((long) i + 1, "cid-" + (i + 1)));
        }
        return events;
    }

    private AlarmStopEvent buildEvent(Long id, String alarmCid) {
        AlarmStopEvent event = new AlarmStopEvent();
        event.setId(id);
        event.setAlarmCid(alarmCid);
        event.setStopTime(new Date());
        event.setEventVersion(0L);
        return event;
    }

    private AlarmCidRoute buildRoute(String alarmCid, Long alarmId, String tableSuffix) {
        AlarmCidRoute route = new AlarmCidRoute();
        route.setAlarmCid(alarmCid);
        route.setAlarmId(alarmId);
        route.setTableSuffix(tableSuffix);
        route.setRouteStatus(AlarmCidRoute.STATUS_ACTIVE);
        route.setIndexSource(AlarmCidRoute.SOURCE_HOT);
        return route;
    }

    private List<Long> ids(List<AlarmStopEvent> events) {
        List<Long> ids = new ArrayList<>();
        for (AlarmStopEvent event : events) {
            ids.add(event.getId());
        }
        return ids;
    }
}
