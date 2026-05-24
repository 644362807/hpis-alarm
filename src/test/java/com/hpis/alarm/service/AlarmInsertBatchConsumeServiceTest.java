package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.service.impl.AlarmServiceImpl;
import com.hpis.alarm.service.support.AlarmDeviceCacheMissingException;
import com.hpis.alarm.service.support.AlarmElectrolyticCellInvalidException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmInsertBatchConsumeServiceTest {

    @Mock
    private AlarmServiceImpl alarmService;

    private AlarmBatchProperties properties;
    private AlarmInsertBatchConsumeService service;

    @Before
    public void setUp() {
        properties = new AlarmBatchProperties();
        service = new AlarmInsertBatchConsumeService(properties, alarmService);
    }

    @Test
    public void batchSuccessReturnsSuccessByOriginalIndex() {
        JSONObject first = rawData("A-1");
        JSONObject second = rawData("A-2");
        AlarmServiceImpl.AlarmInsertContext firstContext = context(first);
        AlarmServiceImpl.AlarmInsertContext secondContext = context(second);
        when(alarmService.prepareAlarmInsertContext(first)).thenReturn(firstContext);
        when(alarmService.prepareAlarmInsertContext(second)).thenReturn(secondContext);

        List<AlarmInsertConsumeResult> results = service.processStartBatch(Arrays.asList(first, second));

        assertEquals(Arrays.asList(AlarmInsertConsumeResult.SUCCESS, AlarmInsertConsumeResult.SUCCESS), results);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(alarmService).persistPreparedAlarmBatch(anyString(), captor.capture());
        assertEquals(2, captor.getValue().size());
        verify(alarmService).releasePreparedAlarmOnFailure(firstContext);
        verify(alarmService).releasePreparedAlarmOnFailure(secondContext);
    }

    @Test
    public void deviceCacheMissingDropsOnlyCurrentItemAndKeepsBatch() {
        JSONObject missing = rawData("A-missing");
        JSONObject normal = rawData("A-normal");
        AlarmServiceImpl.AlarmInsertContext normalContext = context(normal);
        when(alarmService.prepareAlarmInsertContext(missing))
                .thenThrow(new AlarmDeviceCacheMissingException("A-missing", "D-missing", 1, "1"));
        when(alarmService.prepareAlarmInsertContext(normal)).thenReturn(normalContext);

        List<AlarmInsertConsumeResult> results = service.processStartBatch(Arrays.asList(missing, normal));

        assertEquals(Arrays.asList(AlarmInsertConsumeResult.DROP, AlarmInsertConsumeResult.SUCCESS), results);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(alarmService).persistPreparedAlarmBatch(anyString(), captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    public void electrolyticCellInvalidDropsOnlyCurrentItemAndKeepsBatch() {
        JSONObject invalid = rawData("A-invalid-ec");
        JSONObject normal = rawData("A-normal");
        AlarmServiceImpl.AlarmInsertContext normalContext = context(normal);
        when(alarmService.prepareAlarmInsertContext(invalid))
                .thenThrow(new AlarmElectrolyticCellInvalidException("A-invalid-ec", "D-invalid", null, null, "missing field irmsSn"));
        when(alarmService.prepareAlarmInsertContext(normal)).thenReturn(normalContext);

        List<AlarmInsertConsumeResult> results = service.processStartBatch(Arrays.asList(invalid, normal));

        assertEquals(Arrays.asList(AlarmInsertConsumeResult.DROP, AlarmInsertConsumeResult.SUCCESS), results);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(alarmService).persistPreparedAlarmBatch(anyString(), captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    public void skippedContextReturnsSkipAndDoesNotPersist() {
        JSONObject duplicate = rawData("A-duplicate");
        AlarmServiceImpl.AlarmInsertContext skippedContext = context(duplicate);
        skippedContext.setSkipped(true);
        when(alarmService.prepareAlarmInsertContext(duplicate)).thenReturn(skippedContext);

        List<AlarmInsertConsumeResult> results = service.processStartBatch(Arrays.asList(duplicate));

        assertEquals(Arrays.asList(AlarmInsertConsumeResult.SKIP), results);
        verify(alarmService, never()).persistPreparedAlarmBatch(anyString(), anyList());
    }

    @Test
    public void batchFailureFallsBackToPreparedSingleWithoutRePrepare() {
        JSONObject first = rawData("A-1");
        JSONObject second = rawData("A-2");
        AlarmServiceImpl.AlarmInsertContext firstContext = context(first);
        AlarmServiceImpl.AlarmInsertContext secondContext = context(second);
        when(alarmService.prepareAlarmInsertContext(first)).thenReturn(firstContext);
        when(alarmService.prepareAlarmInsertContext(second)).thenReturn(secondContext);
        doThrow(new RuntimeException("batch failed"))
                .when(alarmService).persistPreparedAlarmBatch(anyString(), anyList());

        List<AlarmInsertConsumeResult> results = service.processStartBatch(Arrays.asList(first, second));

        assertEquals(Arrays.asList(AlarmInsertConsumeResult.SUCCESS, AlarmInsertConsumeResult.SUCCESS), results);
        verify(alarmService, times(1)).prepareAlarmInsertContext(first);
        verify(alarmService, times(1)).prepareAlarmInsertContext(second);
        verify(alarmService).persistPreparedAlarmSingle(anyString(), eq(firstContext));
        verify(alarmService).persistPreparedAlarmSingle(anyString(), eq(secondContext));
    }

    @Test
    public void singleFallbackFailureReturnsFailForThatItem() {
        JSONObject first = rawData("A-1");
        JSONObject second = rawData("A-2");
        AlarmServiceImpl.AlarmInsertContext firstContext = context(first);
        AlarmServiceImpl.AlarmInsertContext secondContext = context(second);
        when(alarmService.prepareAlarmInsertContext(first)).thenReturn(firstContext);
        when(alarmService.prepareAlarmInsertContext(second)).thenReturn(secondContext);
        doThrow(new RuntimeException("batch failed"))
                .when(alarmService).persistPreparedAlarmBatch(anyString(), anyList());
        doThrow(new RuntimeException("single failed"))
                .when(alarmService).persistPreparedAlarmSingle(anyString(), eq(secondContext));

        List<AlarmInsertConsumeResult> results = service.processStartBatch(Arrays.asList(first, second));

        assertEquals(Arrays.asList(AlarmInsertConsumeResult.SUCCESS, AlarmInsertConsumeResult.FAIL), results);
        verify(alarmService).releasePreparedAlarmOnFailure(secondContext);
    }

    private JSONObject rawData(String alarmCid) {
        JSONObject rawData = new JSONObject();
        rawData.put("alarmId", alarmCid);
        rawData.put("deviceSn", "D-" + alarmCid);
        rawData.put("sceneType", 1);
        rawData.put("alarmType", 1);
        return rawData;
    }

    private AlarmServiceImpl.AlarmInsertContext context(JSONObject rawData) {
        AlarmServiceImpl.AlarmInsertContext context = new AlarmServiceImpl.AlarmInsertContext();
        context.setJsonObject(rawData);
        return context;
    }
}
