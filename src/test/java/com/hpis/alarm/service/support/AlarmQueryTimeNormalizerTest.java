package com.hpis.alarm.service.support;

import com.hpis.alarm.service.support.AlarmQueryTimeNormalizer.EffectiveTimeRange;
import org.junit.Test;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AlarmQueryTimeNormalizerTest {

    private static final Date FIXED_NOW = new Date(1785427200123L);

    @Test
    public void missingTimesUseCurrentTimeAsEnd() {
        EffectiveTimeRange range = AlarmQueryTimeNormalizer.normalize(null, null, FIXED_NOW);

        assertNull(range.getStartTime());
        assertEquals(FIXED_NOW, range.getEndTime());
        assertTrue(range.isDefaultEndApplied());
        assertFalse(range.isFutureEndClamped());
        assertFalse(range.isInvalidRange());
    }

    @Test
    public void startOnlyKeepsStartAndUsesCurrentEnd() {
        Date startTime = new Date(FIXED_NOW.getTime() - 1000L);

        EffectiveTimeRange range = AlarmQueryTimeNormalizer.normalize(startTime, null, FIXED_NOW);

        assertEquals(startTime, range.getStartTime());
        assertEquals(FIXED_NOW, range.getEndTime());
        assertTrue(range.isDefaultEndApplied());
    }

    @Test
    public void historicalEndIsPreserved() {
        Date endTime = new Date(FIXED_NOW.getTime() - 1000L);

        EffectiveTimeRange range = AlarmQueryTimeNormalizer.normalize(null, endTime, FIXED_NOW);

        assertEquals(endTime, range.getEndTime());
        assertFalse(range.isDefaultEndApplied());
        assertFalse(range.isFutureEndClamped());
    }

    @Test
    public void futureEndIsClampedToCurrentTime() {
        Date futureEnd = new Date(FIXED_NOW.getTime() + 1000L);

        EffectiveTimeRange range = AlarmQueryTimeNormalizer.normalize(null, futureEnd, FIXED_NOW);

        assertEquals(FIXED_NOW, range.getEndTime());
        assertTrue(range.isFutureEndClamped());
    }

    @Test
    public void equalOrReversedRangeIsInvalid() {
        EffectiveTimeRange equal = AlarmQueryTimeNormalizer.normalize(FIXED_NOW, null, FIXED_NOW);
        EffectiveTimeRange reversed = AlarmQueryTimeNormalizer.normalize(
                new Date(FIXED_NOW.getTime() + 1L), null, FIXED_NOW);

        assertTrue(equal.isInvalidRange());
        assertTrue(reversed.isInvalidRange());
    }

    @Test
    public void datesAreDefensivelyCopied() {
        Date startTime = new Date(FIXED_NOW.getTime() - 1000L);
        Date endTime = new Date(FIXED_NOW.getTime() - 1L);
        EffectiveTimeRange range = AlarmQueryTimeNormalizer.normalize(startTime, endTime, FIXED_NOW);

        startTime.setTime(1L);
        endTime.setTime(2L);
        Date returnedEnd = range.getEndTime();
        returnedEnd.setTime(3L);

        assertEquals(new Date(FIXED_NOW.getTime() - 1000L), range.getStartTime());
        assertEquals(new Date(FIXED_NOW.getTime() - 1L), range.getEndTime());
    }
}
