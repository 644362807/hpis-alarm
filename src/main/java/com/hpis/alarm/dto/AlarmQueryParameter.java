package com.hpis.alarm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.data.annotation.Transient;

import java.util.Date;

/**
 * 报警 查询条件 （高复用接口）
 * @author XIANG
 */
@Data
public class AlarmQueryParameter {
    /** 警报类型 */
    @Transient
    private String alarmType;

    /** 开始时间 */
    @Transient
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date startTime;

    /** 结束时间 */
    @Transient
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date endTime;

    /** 设备id */
    @Transient
    private String deviceSn;

    /** 报警行业**/
    private  String sceneType;

    /** 客户id**/
    private Long tenantId;

    private String alarmRank;

    private String alarmStatus;

    private String handleStatus;

    private Long[] deviceIds;
}
