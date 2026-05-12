package com.hpis.alarm.enums;

import java.util.HashMap;
import java.util.Map;

/**
 * 报警推送类别
 * @author PC
 */
public enum SendCategoryEnums {
    SEND_BY_EMAIL("email","邮箱" ),
    SEND_BY_SMS("sms", "短信"),
    SEND_BY_SOUND("sound", "报警器"),
    SEND_BY_WECHAT("wechat","微信");

    private final String key;
    private final String description;
    private static final Map<String, SendCategoryEnums> map = new HashMap<>();

    static {
        for (SendCategoryEnums yn : SendCategoryEnums.values()) {
            map.put(yn.key, yn);
        }
    }

    private SendCategoryEnums(String key, String descriptiion) {
        this.key = key;
        this.description = descriptiion;
    }

    public static String getValue(String key) {
        return fromKey(key).description;
    }

    public static SendCategoryEnums fromKey(String key) {
        if (SendCategoryEnums.map.containsKey(key)) {
            return SendCategoryEnums.map.get(key);
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
