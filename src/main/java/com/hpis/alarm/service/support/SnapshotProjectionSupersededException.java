package com.hpis.alarm.service.support;

/**
 * The claimed snapshot command was replaced while projection was in progress.
 *
 * <p>This is an expected optimistic-concurrency outcome. The old projection
 * must roll back and yield to the newer command without consuming retry quota.</p>
 */
public class SnapshotProjectionSupersededException extends IllegalStateException {

    public SnapshotProjectionSupersededException(String message) {
        super(message);
    }
}
