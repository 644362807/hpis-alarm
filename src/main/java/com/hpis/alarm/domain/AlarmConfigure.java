package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 报警配置对象 alarm_configure
 * 
 * @author 向文来
 * @date 2023-03-28
 */
@Data
public class AlarmConfigure extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 报警配置id */
    @TableId(type = IdType.AUTO)
    private Long alarmConfigureId;

    /** 配置名称 */
    @Excel(name = "配置名称")
    private String alarmConfigureName;

    /** 报警消息类型 */
    @Excel(name = "报警消息类型")
    private String alarmType;

    /** 设备序列号 */
    @TableField(exist = false)
    @Excel(name = "设备序列号")
    private String deviceSn;

    /** 设备报警开关 0关闭 1开启 */
    @Excel(name = "设备报警开关 0关闭 1开启")
    private String deviceAlarmControl;

    /** 报警设置时间 0全天 1自定义时间段 */
    @Excel(name = "报警设置时间 0全天 1自定义时间段")
    private String alarmConfigurePeriod;

    /** 逻辑删除 0存在 2删除 */
    private String delFlag;

    /**  设置时间段*/
    @TableField(exist = false)
    private List<AlarmConfigureTime> alarmConfigureTimeList;

    /**重复报警时长**/
    private Integer repeatAlarmDuration;


    /** 报警行业**/
    private String sceneType;

    /** 客户id**/
    private Long tenantId ;

    @TableField(exist = false)
    private Set<String> deviceSet;

    @TableField(exist = false)
    private Long[] deviceIds;

    /** 重复报警检测周期**/
    private Integer repeatCycleNumber;

    /** 是否进入报警推送：0不推送 1推送 */
    private String pushEnabled;

    /** 推送策略编码，对应推送配置 message_type */
    private String pushMessageType;

    /** 报警工单推送策略编码，对应推送配置 message_type */
    private String workorderPushMessageType;

    /** 关联报警工单模板ID */
    private Long workorderConfigId;

    @TableField(exist = false)
    private String sequenceUid;

    @TableField(exist = false)
    private String irmsSn;


    @Override
    public String toString() {
        return "AlarmConfigure{" +
                "alarmConfigureId=" + alarmConfigureId +
                ", alarmConfigureName='" + alarmConfigureName + '\'' +
                ", alarmType='" + alarmType + '\'' +
                ", deviceSn='" + deviceSn + '\'' +
                ", deviceAlarmControl='" + deviceAlarmControl + '\'' +
                ", alarmConfigurePeriod='" + alarmConfigurePeriod + '\'' +
                ", delFlag='" + delFlag + '\'' +
                ", alarmConfigureTimeList=" + alarmConfigureTimeList +
                ", repeatAlarmDuration=" + repeatAlarmDuration +
                ", sceneType='" + sceneType + '\'' +
                ", tenantId=" + tenantId +
                ", deviceSet=" + deviceSet +
                ", deviceIds=" + Arrays.toString(deviceIds) +
                ", repeatCycleNumber=" + repeatCycleNumber +
                ", pushEnabled='" + pushEnabled + '\'' +
                ", pushMessageType='" + pushMessageType + '\'' +
                ", workorderPushMessageType='" + workorderPushMessageType + '\'' +
                ", workorderConfigId=" + workorderConfigId +
                '}';
    }
}
