package com.hpis.alarm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * @author 局放报警返回表
 */
@Data
public class AlarmPartialDischargeDto {
    /** 主键，关联alarm表 */
    private Long alarmId;

    /** 传感器类型 */
    private Integer sensorType;

    /** 传感器通道类型 */
    private Integer channelIndex;

    /** 传感器id */
    private Integer sensorId;

    /** 0.秒;1.分钟;2.小时 报警周期单位 */
    private String cycleUnit;

    /** 报警频率 */
    private Integer alarmFrequency;

    /** 注意次数 */
    private Integer attentionNumber;

    /** 告警次数 */
    private Integer alarmNumber;

    /** 周期最大幅值 */
    private Float maxAmplitude;

    /** 警报类型 */
    private String alarmType;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date alarmBeginTime;


    /** 设备id */
    private String deviceSn;

    private String targetName;

    private String deviceName;

    private String pdType;

//    public LocalDate getAlarmDateOfDay() {
//        return this.alarmBeginTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
//    }
}
