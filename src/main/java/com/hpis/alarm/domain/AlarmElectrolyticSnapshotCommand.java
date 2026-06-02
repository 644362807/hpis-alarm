package com.hpis.alarm.domain;

import lombok.Data;

import java.util.Date;

/**
 * 电解槽当前点位快照可靠投影命令。
 */
@Data
public class AlarmElectrolyticSnapshotCommand {

    public static final String TYPE_ACTIVE = "ACTIVE";
    public static final String TYPE_DELETED = "DELETED";

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    private String pointHash;

    private String commandType;

    private Long alarmId;

    private Date alarmBeginTime;

    private String payloadJson;

    private String commandStatus;

    private String lockToken;

    private Date lockedAt;

    private Date availableTime;

    private Long version;

    private Integer retryCount;

    private String lastError;

    private Date createdTime;

    private Date updatedTime;
}
