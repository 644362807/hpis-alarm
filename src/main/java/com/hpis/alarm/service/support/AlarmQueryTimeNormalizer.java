package com.hpis.alarm.service.support;

import java.util.Date;

/**
 * Normalizes alarm query time bounds before SQL and sharding routing are built.
 */
public final class AlarmQueryTimeNormalizer {

    private AlarmQueryTimeNormalizer() {
    }

    public static EffectiveTimeRange normalize(Date startTime, Date endTime, Date queryNow) {
        if (queryNow == null) {
            throw new IllegalArgumentException("queryNow must not be null");
        }

        boolean defaultEndApplied = endTime == null;
        boolean futureEndClamped = endTime != null && endTime.after(queryNow);
        Date effectiveStart = copy(startTime);
        Date effectiveEnd = copy(defaultEndApplied || futureEndClamped ? queryNow : endTime);
        boolean invalidRange = effectiveStart != null && !effectiveStart.before(effectiveEnd);

        return new EffectiveTimeRange(effectiveStart, effectiveEnd,
                defaultEndApplied, futureEndClamped, invalidRange);
    }

    private static Date copy(Date value) {
        return value == null ? null : new Date(value.getTime());
    }

    public static final class EffectiveTimeRange {

        private final Date startTime;
        private final Date endTime;
        private final boolean defaultEndApplied;
        private final boolean futureEndClamped;
        private final boolean invalidRange;

        private EffectiveTimeRange(Date startTime, Date endTime,
                                   boolean defaultEndApplied, boolean futureEndClamped,
                                   boolean invalidRange) {
            this.startTime = copy(startTime);
            this.endTime = copy(endTime);
            this.defaultEndApplied = defaultEndApplied;
            this.futureEndClamped = futureEndClamped;
            this.invalidRange = invalidRange;
        }

        public Date getStartTime() {
            return copy(startTime);
        }

        public Date getEndTime() {
            return copy(endTime);
        }

        public boolean isDefaultEndApplied() {
            return defaultEndApplied;
        }

        public boolean isFutureEndClamped() {
            return futureEndClamped;
        }

        public boolean isInvalidRange() {
            return invalidRange;
        }
    }
}
