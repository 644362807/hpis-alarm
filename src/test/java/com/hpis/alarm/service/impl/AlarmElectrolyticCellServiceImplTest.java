package com.hpis.alarm.service.impl;

import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.mapper.AlarmElectrolyticCellMapper;
import com.hpis.alarm.service.AlarmElectrolyticSnapshotCommandService;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlarmElectrolyticCellServiceImplTest {

    @Test
    public void normalizeEctypeItemsKeepsLatestPointAndUsesStableOrder() {
        AlarmElectrolyticCell first = point("irms-b", "seq", 1, 1, "2", 1, 10L);
        AlarmElectrolyticCell second = point("irms-a", "seq", 1, 1, "2", 1, 20L);
        AlarmElectrolyticCell latest = point("irms-b", "seq", 1, 1, "2", 1, 30L);

        List<AlarmElectrolyticCell> normalized =
                AlarmElectrolyticCellServiceImpl.normalizeEctypeItems(Arrays.asList(first, second, latest));

        assertEquals(2, normalized.size());
        assertSame(second, normalized.get(0));
        assertSame(latest, normalized.get(1));
    }

    @Test
    public void insertEctypeListUsesConfiguredAtomicUpsertChunksAtOneHundred() throws Exception {
        AlarmElectrolyticCellMapper mapper = mock(AlarmElectrolyticCellMapper.class);
        when(mapper.insertAlarmElectrolyticCellEctypeList(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).size());
        AlarmElectrolyticCellServiceImpl service = new AlarmElectrolyticCellServiceImpl();
        inject(service, "alarmElectrolyticCellMapper", mapper);
        inject(service, "alarmBatchProperties", new AlarmBatchProperties());
        List<AlarmElectrolyticCell> items = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            items.add(point("irms", "seq", i, 1, "2", 1, (long) i));
        }

        int inserted = service.insertAlarmElectrolyticCellEctypeList(items);

        assertEquals(501, inserted);
        ArgumentCaptor<List<AlarmElectrolyticCell>> chunks = ArgumentCaptor.forClass(List.class);
        verify(mapper, times(6)).insertAlarmElectrolyticCellEctypeList(chunks.capture());
        assertTrue(chunks.getAllValues().stream().allMatch(chunk -> chunk.size() <= 100));
    }

    @Test
    public void asyncSnapshotModeOnlyWritesReliableCommands() throws Exception {
        AlarmElectrolyticCellMapper mapper = mock(AlarmElectrolyticCellMapper.class);
        AlarmElectrolyticSnapshotCommandService commandService = mock(AlarmElectrolyticSnapshotCommandService.class);
        when(commandService.enqueueActive(anyList())).thenReturn(1);
        AlarmBatchProperties properties = new AlarmBatchProperties();
        properties.setElectrolyticSnapshotMode("ASYNC");
        AlarmElectrolyticCellServiceImpl service = new AlarmElectrolyticCellServiceImpl();
        inject(service, "alarmElectrolyticCellMapper", mapper);
        inject(service, "alarmBatchProperties", properties);
        inject(service, "snapshotCommandService", commandService);

        assertEquals(1, service.insertAlarmElectrolyticCellEctypeList(
                Arrays.asList(point("irms", "seq", 1, 1, "2", 1, 10L))));

        verify(commandService).enqueueActive(anyList());
        verify(mapper, never()).insertAlarmElectrolyticCellEctypeList(anyList());
    }

    @Test
    public void asyncDeleteFallsBackToProjectionForLegacyRowWithoutCommand() throws Exception {
        AlarmElectrolyticCellMapper mapper = mock(AlarmElectrolyticCellMapper.class);
        AlarmElectrolyticSnapshotCommandService commandService = mock(AlarmElectrolyticSnapshotCommandService.class);
        when(commandService.enqueueDelete(10L)).thenReturn(0);
        when(mapper.deleteAlarmElectrolyticCellEctypeById(10L)).thenReturn(1);
        AlarmBatchProperties properties = new AlarmBatchProperties();
        properties.setElectrolyticSnapshotMode("ASYNC");
        AlarmElectrolyticCellServiceImpl service = new AlarmElectrolyticCellServiceImpl();
        inject(service, "alarmElectrolyticCellMapper", mapper);
        inject(service, "alarmBatchProperties", properties);
        inject(service, "snapshotCommandService", commandService);

        assertEquals(1, service.deleteAlarmElectrolyticCellEctypeById(10L));

        verify(commandService).enqueueDelete(10L);
        verify(mapper).deleteAlarmElectrolyticCellEctypeById(10L);
    }

    @Test
    public void asyncSingleFallbackOnlyWritesReliableCommand() throws Exception {
        AlarmElectrolyticCellMapper mapper = mock(AlarmElectrolyticCellMapper.class);
        AlarmElectrolyticSnapshotCommandService commandService = mock(AlarmElectrolyticSnapshotCommandService.class);
        when(commandService.enqueueActive(anyList())).thenReturn(1);
        AlarmBatchProperties properties = new AlarmBatchProperties();
        properties.setElectrolyticSnapshotMode("ASYNC");
        AlarmElectrolyticCellServiceImpl service = new AlarmElectrolyticCellServiceImpl();
        inject(service, "alarmElectrolyticCellMapper", mapper);
        inject(service, "alarmBatchProperties", properties);
        inject(service, "snapshotCommandService", commandService);
        AlarmElectrolyticCell item = point("irms", "seq", 1, 1, "2", 1, 10L);

        assertEquals(1, service.insertAlarmElectrolyticCellEctype(item));

        ArgumentCaptor<List<AlarmElectrolyticCell>> commands = ArgumentCaptor.forClass(List.class);
        verify(commandService).enqueueActive(commands.capture());
        assertEquals(Arrays.asList(item), commands.getValue());
        verify(mapper, never()).insertAlarmElectrolyticCellEctype(item);
    }

    @Test
    public void dualWriteSingleFallbackWritesCommandAndProjection() throws Exception {
        AlarmElectrolyticCellMapper mapper = mock(AlarmElectrolyticCellMapper.class);
        AlarmElectrolyticSnapshotCommandService commandService = mock(AlarmElectrolyticSnapshotCommandService.class);
        when(commandService.enqueueActive(anyList())).thenReturn(1);
        when(mapper.insertAlarmElectrolyticCellEctype(org.mockito.ArgumentMatchers.any())).thenReturn(1);
        AlarmBatchProperties properties = new AlarmBatchProperties();
        properties.setElectrolyticSnapshotMode("DUAL_WRITE");
        AlarmElectrolyticCellServiceImpl service = new AlarmElectrolyticCellServiceImpl();
        inject(service, "alarmElectrolyticCellMapper", mapper);
        inject(service, "alarmBatchProperties", properties);
        inject(service, "snapshotCommandService", commandService);
        AlarmElectrolyticCell item = point("irms", "seq", 1, 1, "2", 1, 10L);

        assertEquals(1, service.insertAlarmElectrolyticCellEctype(item));

        verify(commandService).enqueueActive(anyList());
        verify(mapper).insertAlarmElectrolyticCellEctype(item);
    }

    private static AlarmElectrolyticCell point(String irmsSn, String sequenceId, int rowIndex, int grooveNumber,
                                               String observationPlace, int subdivideNumber, Long alarmId) {
        AlarmElectrolyticCell item = new AlarmElectrolyticCell();
        item.setIrmsSn(irmsSn);
        item.setSequenceId(sequenceId);
        item.setRowIndex(rowIndex);
        item.setGrooveNumber(grooveNumber);
        item.setObservationPlace(observationPlace);
        item.setSubdivideNumber(subdivideNumber);
        item.setAlarmId(alarmId);
        return item;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
