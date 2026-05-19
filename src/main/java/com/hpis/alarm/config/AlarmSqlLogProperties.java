package com.hpis.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SQL log controls for the custom MyBatis interceptor.
 */
@Data
@Component
@ConfigurationProperties(prefix = "alarm.sql-log")
public class AlarmSqlLogProperties {

    public static final String MODE_ALL = "all";
    public static final String MODE_ALARM_WRITE = "alarm-write";
    public static final String MODE_SLOW = "slow";

    /** Global SQL info log switch. false means no SQL info is printed. */
    private boolean enabled = false;

    /** all, alarm-write, or slow. */
    private String mode = MODE_ALARM_WRITE;

    /** Whether Params and Full SQL are printed for logged SQL. */
    private boolean printParam = false;

    /** Whether slow SQL can be printed when SQL logging is enabled. */
    private boolean slowEnabled = true;

    /** Slow SQL threshold in milliseconds. */
    private long slowMs = 200L;

    public String normalizedMode() {
        return mode == null || mode.trim().isEmpty() ? MODE_ALARM_WRITE : mode.trim().toLowerCase();
    }
}
