package com.hpis.alarm.util;

import java.time.LocalDateTime;

/**
 * @author 向
 */
public class AppAlarmTimeConversion {

    public static LocalDateTime timeConversion(String appAlarmTime) {
        if ("one_day".equals(appAlarmTime)){
            // 获取当前时间
            LocalDateTime now = LocalDateTime.now();

            // 获取 24 小时前的时间
            LocalDateTime time24HoursAgo = now.minusHours(24);
            return time24HoursAgo;
        }
        if ("three_days".equals(appAlarmTime)){
            // 获取当前时间
            LocalDateTime now = LocalDateTime.now();

            // 获取 3 天前的时间
            LocalDateTime time3DaysAgo = now.minusDays(3);
            return  time3DaysAgo;
        }
        if ("week".equals(appAlarmTime)){
            // 获取当前时间
            LocalDateTime now = LocalDateTime.now();

            // 获取一个星期前的时间
            LocalDateTime oneWeekAgo = now.minusWeeks(1);
            return oneWeekAgo;
        }
            // 获取当前时间
            LocalDateTime now = LocalDateTime.now();

            // 获取 1 个月前的时间
            LocalDateTime oneMonthAgo = now.minusMonths(1);
            return oneMonthAgo;
    }
}
