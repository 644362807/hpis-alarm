package com.hpis.alarm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.common.core.annotation.Excel;
import lombok.Data;

import java.util.Date;

/**
 * @author xiang
 */
@Data
public class RepeatAlarmDto {

    /** 唯一警报号id */
    private Long alarmId;


    /** 处理时间**/
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date handleTime;

    /** 报警处理人id */
    @Excel(name = "报警处理人id")
    private Long handlerId;

    /** 处理状态-1等待处理，0未处理 1已处理 */
    @Excel(name = "处理状态-1等待处理，0未处理 1已处理")
    private String handleStatus;

    /**重复报警次数 **/
    private int repeatNumber;

    /** 重复报警时间**/
    private String repeatTime;

    private Date createTime;

    private String repeatHandlerUsers;

    private String repeatHandleTime;

    private String handlerName;
}
