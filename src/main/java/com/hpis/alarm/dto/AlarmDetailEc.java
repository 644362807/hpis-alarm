package com.hpis.alarm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.common.core.annotation.Excel;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * @author PC
 */
@Data
public class AlarmDetailEc {
    /**
     * 主键，关联alarm表
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long alarmId;

    /**
     * 序列唯一标识
     */
    private String sequenceId;

    /**
     * 跨号
     */
    private Integer rowIndex;

    /**
     * 槽号
     */
    private Integer grooveNumber;

    /**
     * 细分号（导电条号或极板号）
     */
    private Integer subdivideNumber;

    /**
     * 观察位置
     */
    private String observationPlace;

    /**
     * 温度
     */
    private BigDecimal temperatureVariation;

    /**
     * 设备名称
     */
    private String deviceName;

    /**
     * 警报类型
     */
    private String alarmType;

    /**
     * 警报级别
     */
    private String alarmRank;

    /**
     * 开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date startDate;

    /**
     * 结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date endDate;

    /**
     * 极板号
     */
    private Integer electrodesNumber;

    /**
     * 导电条号
     */
    private Integer busBarsNumber;

    /**
     * 事件状态
     *
     * @return
     */
    private String alarmStatus;

    /**
     * 序列名称
     */
    private String sequenceName;

    /**
     * 未停止报警标识0
     */
    private Integer stopAlarmFlag;

    /**
     * 行业id
     **/
    private String sceneType;

    /**
     * 开始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date alarmBeginTime;

    /**
     * 结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date alarmEndTime;

    /**
     * 报警图片地址
     */
    private String picturePath;


    /**
     * 报警处理意见
     **/
    private String opinion;


    private String alarmCid;

    private String irmsSn;


    private String targetName;
    /**
     * 事件源
     */
    private String alarmSource;

    /**
     * 槽内首块电极板正负极（正极=1，负极=2）
     */
    private String firstElectrodesPolarity;

    private String deviceSn;

    private Integer repeatNumber;

    private String repeatTime;

    private String handleStatus;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date handleTime;

    private Long handlerId;

    /**巡检仪标识 **/
    private String apparatusId;

    /** 一般报警 */
    @Excel(name = "一般报警")
    private String generalAlarm;

    /** 紧急报警 */
    @Excel(name = "紧急报警")
    private String emergencyAlarm;

    /** 严重报警 */
    @Excel(name = "严重报警")
    private String criticalAlarm;

    private String repeatHandleTime;

    private String repeatHandlerUsers;

    private String handlerName;

    private String handlePicture;

    private Long confirmUserId;

}
