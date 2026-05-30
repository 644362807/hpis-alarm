package com.hpis.alarm.service.support;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AlarmBatchChunkerTest {

    @Test
    public void clampsInvalidAndOversizedBatchValues() {
        assertEquals(500, AlarmBatchChunker.safeBatchSize(0));
        assertEquals(500, AlarmBatchChunker.safeBatchSize(-1));
        assertEquals(200, AlarmBatchChunker.safeBatchSize(200));
        assertEquals(500, AlarmBatchChunker.safeBatchSize(2000));
    }

    @Test
    public void chunksOversizedInputAtHardLimit() {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 1201; i++) {
            values.add(i);
        }

        List<List<Integer>> chunks = AlarmBatchChunker.chunk(values, 2000);

        assertEquals(3, chunks.size());
        assertEquals(500, chunks.get(0).size());
        assertEquals(500, chunks.get(1).size());
        assertEquals(201, chunks.get(2).size());
    }
}
