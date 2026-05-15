package com.hpis.alarm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 事件明细
 */
@Data
public class AlarmElectrolyticCellRecord {

    /**
     * 设备名称
     */
    private String deviceName;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date alarmBegintime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date alarmEndtime;

    /** 警报类型 */
    private String alarmType;

    /** 警报级别 */
    private String alarmRank;

    /** 序列名称 */
    private String sequenceName;

    /** 跨号 */
    private Integer rowIndex;

    /** 槽号 */
    private Integer grooveNumber;

    /**
     * 事件源
     */
    private String alarmSource;

    /** 槽内首块电极板正负极（正极=1，负极=2） */
    private String firstElectrodesPolarity;

    /** 细分号（导电条号或极板号） */
    private Integer subdivideNumber;

    /** 观察位置 */
    private String observationPlace;

}
