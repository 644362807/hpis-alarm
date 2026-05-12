package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlarmSend extends BaseEntity {
    private static final long serialVersionUID = 1L;


    /** 配置id */
    @TableId(type = IdType.AUTO)
    private Long alarmSendId;
    /** 设备id */
    private Long deviceId;
    /** 报警开关 1-开启 0-关闭 */
    private String deviceAlarmControl;
    /** 报警方式 */
    private String alarmMethod;
    /** 报警类别 0-5逗号分隔 */
    private String alarmCategories;
    /** 报警发送目标 */
    private String alarmTargets;
    /** 是否全天 1-全天 0-指定时间 */
    private String isAllDay;
    /** 报警时间段 */
    private String alarmTimePeriods;


    public Long getAlarmSendId() {
        return alarmSendId;
    }

    public void setAlarmSendId(Long alarmSendId) {
        this.alarmSendId = alarmSendId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Long deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceAlarmControl() {
        return deviceAlarmControl;
    }

    public void setDeviceAlarmControl(String deviceAlarmControl) {
        this.deviceAlarmControl = deviceAlarmControl;
    }

    public String getAlarmMethod() {
        return alarmMethod;
    }

    public void setAlarmMethod(String alarmMethod) {
        this.alarmMethod = alarmMethod;
    }

    public String getAlarmCategories() {
        return alarmCategories;
    }

    public void setAlarmCategories(String alarmCategories) {
        this.alarmCategories = alarmCategories;
    }

    public String getAlarmTargets() {
        return alarmTargets;
    }

    public void setAlarmTargets(String alarmTargets) {
        this.alarmTargets = alarmTargets;
    }

    public String getIsAllDay() {
        return isAllDay;
    }

    public void setIsAllDay(String isAllDay) {
        this.isAllDay = isAllDay;
    }

    public String getAlarmTimePeriods() {
        return alarmTimePeriods;
    }

    public void setAlarmTimePeriods(String alarmTimePeriods) {
        this.alarmTimePeriods = alarmTimePeriods;
    }
}
