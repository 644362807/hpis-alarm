package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;

import java.util.Arrays;
import java.util.Date;

/**
 * 报警时间对象 alarm_configure_time
 * 
 * @author 向文来
 * @date 2023-03-28
 */
@Data
public class AlarmConfigureTime extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 报警设置编号 */
    @Excel(name = "报警设置编号")
    @TableId(type = IdType.AUTO)
    private Long alarmConfigureId;

    /** 报警设置开始时间 */
    @JsonFormat(pattern = "HH:mm:ss")
    @Excel(name = "报警设置开始时间", width = 30, dateFormat = "HH:mm:ss")
    private Date alarmConfigureStarttime;

    /** 报警设备结束时间 */
    @JsonFormat(pattern = "HH:mm:ss")
    @Excel(name = "报警设备结束时间", width = 30, dateFormat = "HH:mm:ss")
    private Date alarmConfigureEndtime;

    /** 逻辑删除 0存在 2删除 */
    private String delFlag;

    private String[] time;

    public String[] getTime() {
        return time;
    }

    public void setTime(String[] time) {
        this.time = time;
    }

    public void setAlarmConfigureId(Long alarmConfigureId)
    {
        this.alarmConfigureId = alarmConfigureId;
    }

    public Long getAlarmConfigureId() 
    {
        return alarmConfigureId;
    }
    public void setAlarmConfigureStarttime(Date alarmConfigureStarttime)
    {
        this.alarmConfigureStarttime = alarmConfigureStarttime;
    }

    public Date getAlarmConfigureStarttime()
    {
        return alarmConfigureStarttime;
    }
    public void setAlarmConfigureEndtime(Date alarmConfigureEndtime)
    {
        this.alarmConfigureEndtime = alarmConfigureEndtime;
    }

    public Date getAlarmConfigureEndtime()
    {
        return alarmConfigureEndtime;
    }
    public void setDelFlag(String delFlag)
    {
        this.delFlag = delFlag;
    }

    public String getDelFlag() 
    {
        return delFlag;
    }

    @Override
    public String toString() {
        return "AlarmConfigureTime{" +
                "alarmConfigureId=" + alarmConfigureId +
                ", alarmConfigureStarttime=" + alarmConfigureStarttime +
                ", alarmConfigureEndtime=" + alarmConfigureEndtime +
                ", delFlag='" + delFlag + '\'' +
                ", time=" + Arrays.toString(time) +
                '}';
    }
}