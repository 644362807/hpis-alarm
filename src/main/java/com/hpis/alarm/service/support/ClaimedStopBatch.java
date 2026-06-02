package com.hpis.alarm.service.support;

import com.hpis.alarm.domain.AlarmStopEvent;

import java.util.Collections;
import java.util.List;

/**
 * 一次短事务认领得到的 stop event 批次。
 */
public final class ClaimedStopBatch {

    private final String lockToken;
    private final List<AlarmStopEvent> events;

    public ClaimedStopBatch(String lockToken, List<AlarmStopEvent> events) {
        this.lockToken = lockToken;
        this.events = events == null ? Collections.emptyList() : events;
    }

    public String getLockToken() {
        return lockToken;
    }

    public List<AlarmStopEvent> getEvents() {
        return events;
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }
}
