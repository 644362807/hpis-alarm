package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.config.AlarmInternalTestProperties;
import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.domain.AlarmStopSideEffectEvent;
import com.hpis.alarm.mapper.AlarmStopEventMapper;
import com.hpis.alarm.mapper.AlarmStopSideEffectMapper;
import com.hpis.device.api.RemoteIrChannelService;
import com.hpis.device.api.RemoteTmService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmStopSideEffectServiceTest {

    @Mock
    private AlarmStopSideEffectMapper sideEffectMapper;
    @Mock
    private AlarmStopEventMapper stopEventMapper;
    @Mock
    private RemoteIrChannelService remoteIrChannelService;
    @Mock
    private RemoteTmService remoteTmService;
    @Mock
    private IAlarmElectrolyticCellService alarmElectrolyticCellService;

    @Test
    public void processPendingBatchClampsSelectAndDoneUpdateToFiveHundred() {
        AlarmBatchProperties batchProperties = new AlarmBatchProperties();
        AlarmStopWorkerProperties workerProperties = new AlarmStopWorkerProperties();
        workerProperties.setNormalBatchSize(2000);
        AlarmInternalTestProperties internalTestProperties = new AlarmInternalTestProperties();
        internalTestProperties.setRemoteCallStubEnabled(true);
        AlarmStopSideEffectService service = new AlarmStopSideEffectService(sideEffectMapper, stopEventMapper,
                batchProperties, workerProperties, remoteIrChannelService, remoteTmService,
                alarmElectrolyticCellService, internalTestProperties);
        when(stopEventMapper.countPending()).thenReturn(0);
        when(sideEffectMapper.selectPendingBatch(500)).thenReturn(buildEvents(501));

        int done = service.processPendingBatch();

        assertEquals(501, done);
        verify(sideEffectMapper).selectPendingBatch(500);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(sideEffectMapper, times(2)).markDoneBatch(captor.capture());
        assertEquals(500, captor.getAllValues().get(0).size());
        assertEquals(1, captor.getAllValues().get(1).size());
    }

    private List<AlarmStopSideEffectEvent> buildEvents(int size) {
        List<AlarmStopSideEffectEvent> events = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            AlarmStopSideEffectEvent event = new AlarmStopSideEffectEvent();
            event.setId((long) i + 1);
            event.setAlarmCid("cid-" + (i + 1));
            event.setEffectType(AlarmStopSideEffectEvent.EFFECT_IR_OFFLINE_RECOVER);
            event.setPayloadJson(new JSONObject().toJSONString());
            events.add(event);
        }
        return events;
    }
}
