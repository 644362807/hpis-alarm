package com.hpis.alarm.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * @author Xiang
 */

public enum AlarmStatusEnums {
    ALARM_STATUS_ENUMS_999("-1", "误报"),

    ALARM_STATUS_ENUMS_0("0", "未处理"),

    ALARM_STATUS_ENUMS_1("1", "已停止"),

    ALARM_STATUS_ENUMS_2("2", "已处理");


    private final String key;
    private final String description;
    private static final Map<String, AlarmStatusEnums> map = new HashMap<>();

    static {
        for (AlarmStatusEnums yn : AlarmStatusEnums.values()) {
            map.put(yn.key, yn);
        }
    }

    private AlarmStatusEnums(String key, String descriptiion) {
        this.key = key;
        this.description = descriptiion;
    }

    public static String getValue(String key) {
        return fromKey(key).description;
    }

    public static AlarmStatusEnums fromKey(String key) {
        if (AlarmStatusEnums.map.containsKey(key)) {
            return AlarmStatusEnums.map.get(key);
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
