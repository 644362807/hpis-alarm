package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;
import org.springframework.data.annotation.Transient;

import java.util.Date;

/**
 * 局放报警详情对象 alarm_partial_discharge
 * 
 * @author ruoyi
 * @date 2024-03-13
 */
@Data
public class AlarmPartialDischarge extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键，关联alarm表 */
    @TableId(type = IdType.AUTO)
    private Long alarmId;

    /** 传感器类型 */
    @Excel(name = "传感器类型")
    private Integer sensorType;

    /** 传感器通道类型 */
    @Excel(name = "传感器通道类型")
    private Integer channelIndex;

    /** 传感器id */
    @Excel(name = "传感器id")
    private Integer sensorId;

    /** 0.秒;1.分钟;2.小时 报警周期单位 */
    @Excel(name = "0.秒;1.分钟;2.小时 报警周期单位")
    private String cycleUnit;

    /** 报警频率 */
    @Excel(name = "报警频率")
    private Integer alarmFrequency;

    /** 注意次数 */
    @Excel(name = "注意次数")
    private Integer attentionNumber;

    /** 告警次数 */
    @Excel(name = "告警次数")
    private Integer alarmNumber;

    /** 周期最大幅值 */
    @Excel(name = "周期最大幅值")
    private Float maxAmplitude;

    /** 警报类型 */
    @Transient
    private String alarmType;


    /** 设备id */
    @Transient
    private String deviceSn;

    private String pdType;

    @Transient
    private String prpdData;

    private Long tenantId;

    private String sceneType;


    @Override
    public String toString() {
        return "AlarmPartialDischarge{" +
                "alarmId=" + alarmId +
                ", sensorType=" + sensorType +
                ", channelIndex=" + channelIndex +
                ", sensorId=" + sensorId +
                ", cycleUnit='" + cycleUnit + '\'' +
                ", alarmFrequency=" + alarmFrequency +
                ", attentionNumber=" + attentionNumber +
                ", alarmNumber=" + alarmNumber +
                ", maxAmplitude=" + maxAmplitude +
                ", alarmType='" + alarmType + '\'' +
                ", tenantId=" + tenantId +
                ", sceneType='" + sceneType + '\'' +
                '}';
    }
}