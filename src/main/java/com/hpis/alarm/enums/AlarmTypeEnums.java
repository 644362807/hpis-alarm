package com.hpis.alarm.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * 报警类型
 * @author xwl
 */

public enum AlarmTypeEnums {
    ALARM_TYPE_ENUMS_0("0", "一般行业"),
    //高温报警
    ALARM_TYPE_ENUMS_1("1", "0"),
    //在线局放
    ALARM_TYPE_ENUMS_2("2", "局放报警"),
    ALARM_TYPE_ENUMS_3("3", "集热器行业"),
    ALARM_TYPE_ENUMS_4("4", "回转窑行业"),
    ALARM_TYPE_ENUMS_5("5", "电力行业"),
    //断线报警
    ALARM_TYPE_ENUMS_6("6", "3"),
    ALARM_TYPE_ENUMS_7("7", "颜色报警"),
    // 紧急报警类型由字典配置为 10，推送组装阶段也必须能够安全解析。
    ALARM_TYPE_ENUMS_10("10", "紧急报警"),
    //电解槽电压报警
    ALARM_TYPE_ENUMS_14("14", "电压报警"),
    ALARM_TYPE_ENUMS_100("20","重复报警");

    private final String key;
    private final String description;
    private static final Map<String, AlarmTypeEnums> map = new HashMap<>();

    static {
        for (AlarmTypeEnums yn : AlarmTypeEnums.values()) {
            map.put(yn.key, yn);
        }
    }

    private AlarmTypeEnums(String key, String descriptiion) {
        this.key = key;
        this.description = descriptiion;
    }

    public static String getValue(String key) {
        AlarmTypeEnums value = fromKey(key);
        // 字典可能先于代码扩展新报警类型；未知值沿用原始 key，不能阻断报警持久化后的推送。
        return value == null ? key : value.description;
    }

    public static AlarmTypeEnums fromKey(String key) {
        if (AlarmTypeEnums.map.containsKey(key)) {
            return AlarmTypeEnums.map.get(key);
        }
        return null;
    }

    public String getKey() {
        return this.key;
    }

    public String getDescription() {
        return this.description;
    }
}
