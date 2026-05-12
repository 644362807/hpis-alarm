package com.hpis.alarm.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * @author xiang
 */

public enum HandleStatusEnums {

    ALARM_STATUS_ENUMS_0("0", "未处理"),

    ALARM_STATUS_ENUMS_1("1", "已处理"),

    ALARM_STATUS_ENUMS_2("2", "已确认");

    private final String key;
    private final String description;
    private static final Map<String, HandleStatusEnums> map = new HashMap<>();

    static {
        for (HandleStatusEnums yn : HandleStatusEnums.values()) {
            map.put(yn.key, yn);
        }
    }

    private HandleStatusEnums(String key, String descriptiion) {
        this.key = key;
        this.description = descriptiion;
    }

    public static String getValue(String key) {
        return fromKey(key).description;
    }

    public static HandleStatusEnums fromKey(String key) {
        if (HandleStatusEnums.map.containsKey(key)) {
            return HandleStatusEnums.map.get(key);
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
