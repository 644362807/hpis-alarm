package com.hpis.alarm.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;
import org.springframework.data.annotation.Transient;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

/**
 * 电解槽关联报警对象 alarm_electrolytic_cell
 *
 * @author ruoyi
 * @date 2023-04-25
 */
@Data
public class AlarmElectrolyticCell extends BaseEntity
{
    private static final long serialVersionUID = 1L;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    /** 主键，关联alarm表 */
    private Long alarmId;

    /**
     * 报警开始时间。
     *
     * <p>电解槽关联表原来只依赖 alarm_id 和主表 join。时间容量分片后，子表路由需要让
     * alarm_electrolytic_cell 与 alarm 使用同一个 table_suffix，因此写入时同步保存
     * alarm_beginTime。</p>
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date alarmBegintime;

    /** 序列唯一标识 */
    private String sequenceId;

    /** 跨号 */
    private Integer rowIndex;

    /** 槽号 */
    private Integer grooveNumber;

    /** 细分号（导电条号或极板号） */
    private Integer subdivideNumber;

    /** 观察位置 */
    @Excel(name = "观察位置")
    private String observationPlace;

    /** 温度 */
    private BigDecimal temperatureVariation;

    /** 设备名称 */
    @Transient
    private String deviceName;

    /** 警报类型 */
    @Transient
    private String alarmType;

    /** 警报级别 */
    @Transient
    private String alarmRank;

    /** 开始时间 */
    @Transient
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startDate;

    /** 结束时间 */
    @Transient
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endDate;

    /** 极板号 */
    @Transient
    private Integer electrodesNumber;

    /** 导电条号 */
    @Transient
    private Integer busBarsNumber;

    /**
     * 事件状态
     * @return
     */
    @Transient
    private String alarmStatus;

    /**
     * 序列名称
     * @return
     */
    @Transient
    private String sequenceName;

    /**
     * 未停止报警标识0
     */
    @Transient
    private Integer stopAlarmFlag;

    /**行业id**/
    @Transient
    private String sceneType;

    /**部位**/
    @Transient
    private String targetName;

    private String deviceSn;

    private int repeatNumber;

    private String repeatTime;

    private String[]  alarmTypes;

    private String repeatHandlerUsers;

    private String repeatHandleTime;

    private Long handlerId;

    private String handleStatus;

    private String selectEnd;

    private String irmsSn;

    /** 客户id**/
    @Transient
    private Long  tenantId;

    @Override
    public String toString() {
        return "AlarmElectrolyticCell{" +
                "alarmId=" + alarmId +
                ", alarmBegintime=" + alarmBegintime +
                ", sequenceId='" + sequenceId + '\'' +
                ", rowIndex=" + rowIndex +
                ", grooveNumber=" + grooveNumber +
                ", subdivideNumber=" + subdivideNumber +
                ", observationPlace='" + observationPlace + '\'' +
                ", temperatureVariation=" + temperatureVariation +
                ", deviceName='" + deviceName + '\'' +
                ", alarmType='" + alarmType + '\'' +
                ", alarmRank='" + alarmRank + '\'' +
                ", startDate=" + startDate +
                ", endDate=" + endDate +
                ", electrodesNumber=" + electrodesNumber +
                ", busBarsNumber=" + busBarsNumber +
                ", alarmStatus='" + alarmStatus + '\'' +
                ", sequenceName='" + sequenceName + '\'' +
                ", stopAlarmFlag=" + stopAlarmFlag +
                ", sceneType='" + sceneType + '\'' +
                ", targetName='" + targetName + '\'' +
                ", deviceSn='" + deviceSn + '\'' +
                ", repeatNumber=" + repeatNumber +
                ", repeatTime='" + repeatTime + '\'' +
                ", alarmTypes=" + Arrays.toString(alarmTypes) +
                ", repeatHandlerUsers='" + repeatHandlerUsers + '\'' +
                ", repeatHandleTime='" + repeatHandleTime + '\'' +
                ", handlerId=" + handlerId +
                ", handleStatus='" + handleStatus + '\'' +
                ", selectEnd='" + selectEnd + '\'' +
                ", irmsSn='" + irmsSn + '\'' +
                ", tenantId=" + tenantId +
                '}';
    }
}
