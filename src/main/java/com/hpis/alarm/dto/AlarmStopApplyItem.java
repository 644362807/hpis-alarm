package com.hpis.alarm.dto;

import lombok.Data;

import java.util.Date;

/**
 * 批量关闭业务分片表时的最小更新单元。
 *
 * <p>同一批 stop event 会先按 table_suffix 分组，再用 alarm_id 直达对应物理分片。
 * 每条报警的 stop_time 可能不同，因此批量 SQL 使用 CASE WHEN 按 alarm_id 写入各自结束时间。</p>
 */
@Data
public class AlarmStopApplyItem {

    private Long eventId;

    private Long alarmId;

    private String alarmCid;

    private Date stopTime;
}
