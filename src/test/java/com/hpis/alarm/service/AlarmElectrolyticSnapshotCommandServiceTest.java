package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmElectrolyticSnapshotWorkerProperties;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.domain.AlarmElectrolyticSnapshotCommand;
import com.hpis.alarm.mapper.AlarmElectrolyticCellMapper;
import com.hpis.alarm.mapper.AlarmElectrolyticSnapshotCommandMapper;
import com.hpis.alarm.task.AlarmElectrolyticSnapshotWorkerSignal;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlarmElectrolyticSnapshotCommandServiceTest {

    @Test
    public void enqueueActiveUsesStablePointHashAndLatestPointPayload() {
        AlarmElectrolyticSnapshotCommandMapper commandMapper = mock(AlarmElectrolyticSnapshotCommandMapper.class);
        AlarmElectrolyticCellMapper cellMapper = mock(AlarmElectrolyticCellMapper.class);
        AlarmElectrolyticSnapshotWorkerProperties properties = new AlarmElectrolyticSnapshotWorkerProperties();
        AlarmElectrolyticSnapshotWorkerSignal signal = mock(AlarmElectrolyticSnapshotWorkerSignal.class);
        AlarmElectrolyticSnapshotCommandService service =
                new AlarmElectrolyticSnapshotCommandService(commandMapper, cellMapper, properties, signal);
        when(commandMapper.upsertActiveBatch(anyList())).thenReturn(1);
        AlarmElectrolyticCell first = point("irms", "seq", 1, 2, "2", 3, 10L);
        AlarmElectrolyticCell latest = point("irms", "seq", 1, 2, "2", 3, 20L);

        assertEquals(1, service.enqueueActive(Arrays.asList(first, latest)));

        ArgumentCaptor<List<AlarmElectrolyticSnapshotCommand>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandMapper).upsertActiveBatch(captor.capture());
        AlarmElectrolyticSnapshotCommand command = captor.getValue().get(0);
        assertEquals(64, command.getPointHash().length());
        assertEquals(Long.valueOf(20L), command.getAlarmId());
        assertEquals(Long.valueOf(20L),
                JSONObject.parseObject(command.getPayloadJson(), AlarmElectrolyticCell.class).getAlarmId());
        assertEquals(AlarmElectrolyticSnapshotCommandService.pointHash(first),
                AlarmElectrolyticSnapshotCommandService.pointHash(latest));
        assertNotEquals(AlarmElectrolyticSnapshotCommandService.pointHash(first),
                AlarmElectrolyticSnapshotCommandService.pointHash(point("irms", "seq", 9, 2, "2", 3, 30L)));
    }

    @Test
    public void processClaimedBatchProjectsActiveDeletesByAlarmAndChecksVersionedDoneCount() {
        AlarmElectrolyticSnapshotCommandMapper commandMapper = mock(AlarmElectrolyticSnapshotCommandMapper.class);
        AlarmElectrolyticCellMapper cellMapper = mock(AlarmElectrolyticCellMapper.class);
        AlarmElectrolyticSnapshotCommandService service =
                new AlarmElectrolyticSnapshotCommandService(commandMapper, cellMapper,
                        new AlarmElectrolyticSnapshotWorkerProperties(),
                        mock(AlarmElectrolyticSnapshotWorkerSignal.class));
        AlarmElectrolyticCell cell = point("irms", "seq", 1, 2, "2", 3, 10L);
        AlarmElectrolyticSnapshotCommand active = command(AlarmElectrolyticSnapshotCommand.TYPE_ACTIVE, cell, 1L);
        AlarmElectrolyticSnapshotCommand deleted = command(AlarmElectrolyticSnapshotCommand.TYPE_DELETED,
                point("irms", "seq", 9, 2, "2", 3, 99L), 2L);
        when(commandMapper.markDoneBatch(eq("token"), anyList())).thenReturn(2);

        assertEquals(2, service.processClaimedBatch("token", Arrays.asList(active, deleted)));

        verify(cellMapper).insertAlarmElectrolyticCellEctypeList(anyList());
        verify(cellMapper).deleteAlarmElectrolyticCellEctypeByIds(Collections.singletonList(99L));
        verify(commandMapper).markDoneBatch("token", Arrays.asList(active, deleted));
    }

    @Test
    public void processClaimedBatchRollsBackWhenTokenVersionDoneCountDiffers() {
        AlarmElectrolyticSnapshotCommandMapper commandMapper = mock(AlarmElectrolyticSnapshotCommandMapper.class);
        AlarmElectrolyticCellMapper cellMapper = mock(AlarmElectrolyticCellMapper.class);
        AlarmElectrolyticSnapshotCommandService service =
                new AlarmElectrolyticSnapshotCommandService(commandMapper, cellMapper,
                        new AlarmElectrolyticSnapshotWorkerProperties(),
                        mock(AlarmElectrolyticSnapshotWorkerSignal.class));
        AlarmElectrolyticSnapshotCommand active = command(AlarmElectrolyticSnapshotCommand.TYPE_ACTIVE,
                point("irms", "seq", 1, 2, "2", 3, 10L), 1L);
        when(commandMapper.markDoneBatch(eq("old-token"), anyList())).thenReturn(0);

        assertThatThrownBy(() -> service.processClaimedBatch("old-token", Collections.singletonList(active)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token/version");
        verify(cellMapper, never()).deleteAlarmElectrolyticCellEctypeByIds(anyList());
    }

    private static AlarmElectrolyticSnapshotCommand command(String type, AlarmElectrolyticCell cell, long version) {
        AlarmElectrolyticSnapshotCommand command = new AlarmElectrolyticSnapshotCommand();
        command.setPointHash(AlarmElectrolyticSnapshotCommandService.pointHash(cell));
        command.setCommandType(type);
        command.setAlarmId(cell.getAlarmId());
        command.setPayloadJson(JSONObject.toJSONString(cell));
        command.setVersion(version);
        return command;
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
}
