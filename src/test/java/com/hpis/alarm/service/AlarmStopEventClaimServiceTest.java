package com.hpis.alarm.service;

import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.domain.AlarmStopEvent;
import com.hpis.alarm.mapper.AlarmStopEventMapper;
import com.hpis.alarm.service.support.ClaimedStopBatch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AlarmStopEventClaimServiceTest {

    @Mock
    private AlarmStopEventMapper stopEventMapper;

    private AlarmStopWorkerProperties properties;
    private AlarmStopEventClaimService service;

    @Before
    public void setUp() {
        properties = new AlarmStopWorkerProperties();
        service = new AlarmStopEventClaimService(stopEventMapper, properties);
    }

    @Test
    public void claimUsesBoundedBatchAndReturnsOnlyOwnTokenRows() {
        AlarmStopEvent event = new AlarmStopEvent();
        event.setId(1L);
        when(stopEventMapper.claimPendingBatch(anyString(), any(Date.class), anyInt())).thenReturn(1);
        when(stopEventMapper.selectProcessingByToken(anyString())).thenReturn(Collections.singletonList(event));

        ClaimedStopBatch batch = service.claimPendingBatch();

        assertFalse(batch.isEmpty());
        assertEquals(Collections.singletonList(event), batch.getEvents());
        verify(stopEventMapper).claimPendingBatch(
                org.mockito.ArgumentMatchers.eq(batch.getLockToken()),
                any(Date.class),
                org.mockito.ArgumentMatchers.eq(properties.safeClaimBatchSize()));
        verify(stopEventMapper).selectProcessingByToken(batch.getLockToken());
    }

    @Test
    public void differentClaimsUseDifferentTokens() {
        when(stopEventMapper.claimPendingBatch(anyString(), any(Date.class), anyInt())).thenReturn(0);

        ClaimedStopBatch first = service.claimPendingBatch();
        ClaimedStopBatch second = service.claimPendingBatch();

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        assertNotEquals(first.getLockToken(), second.getLockToken());
    }

    @Test
    public void releaseClaimDelaysRetryAndKeepsTokenCondition() {
        properties.setProcessingRetryDelayMs(1234L);
        long before = System.currentTimeMillis();

        service.releaseClaim("token-1", new IllegalStateException("db failed"));

        ArgumentCaptor<Date> availableTime = ArgumentCaptor.forClass(Date.class);
        verify(stopEventMapper).releaseProcessingByToken(
                org.mockito.ArgumentMatchers.eq("token-1"),
                org.mockito.ArgumentMatchers.eq("db failed"),
                availableTime.capture(),
                org.mockito.ArgumentMatchers.eq(properties.safeMaxRetry()));
        assertTrue(availableTime.getValue().getTime() >= before + 1200L);
    }

    @Test
    public void recoverExpiredClaimUsesTimeoutAndBoundedBatch() {
        properties.setProcessingTimeoutMs(60000L);
        properties.setClaimRecoveryBatchSize(9999);
        long before = System.currentTimeMillis();

        service.recoverExpiredClaims();

        ArgumentCaptor<Date> lockedBefore = ArgumentCaptor.forClass(Date.class);
        verify(stopEventMapper).releaseExpiredProcessing(lockedBefore.capture(), any(Date.class),
                org.mockito.ArgumentMatchers.eq(500),
                org.mockito.ArgumentMatchers.eq(properties.safeMaxRetry()));
        assertTrue(lockedBefore.getValue().getTime() <= before - 59000L);
    }
}
