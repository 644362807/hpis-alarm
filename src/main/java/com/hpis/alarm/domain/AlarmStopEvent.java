package com.hpis.alarm.domain;

import lombok.Data;

import java.util.Date;

/**
 * 结束报警可靠缓冲事件。
 *
 * <p>alarm_stop_event 不是历史表，只保存“还没被业务分片确认关闭”以及“刚关闭、等待短期清理”
 * 的 stop 消息。MQ stop 消息写入本表成功后即可 ack，后续由 worker 批量写 alarm_endTime。
 * 这样即使业务线程池、远程同步或数据库短时变慢，stop 消息也不会因为 MQ 已 ack 而丢失。</p>
 */
@Data
public class AlarmStopEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;

    /** 外部报警 ID，也就是 MQ rawData.alarmId。默认假设基本全局唯一。 */
    private String alarmCid;

    /** 外部 stop 消息携带的结束时间。 */
    private Date stopTime;

    /** PENDING/APPLIED/FAILED。 */
    private String eventStatus;

    private Integer retryCount;

    private String lastError;

    private Date createdTime;

    private Date updatedTime;

    private Date appliedTime;

    /** APPLIED 后的物理清理时间，只在低流量窗口删除到期数据。 */
    private Date deleteAfter;
}
