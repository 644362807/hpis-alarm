package com.hpis.alarm.dto;

import lombok.Data;

/**
 * 总统计
 * @author 向
 */
@Data
public class AlarmPartialDischargeCount {

    /** 今日注意次数**/
    private int todayAttentionNumber ;
    /**今日报警次数 **/
    private int todayAlarmNumber;
    /** 总注意次数 **/
    private  int allAttentionNumber;
    /** 总报警**/
    private  int allAlarmNumber;

}
