package com.hpis.alarm.enums;


import java.util.HashMap;
import java.util.Map;

/**
 * 报警行业类型
 * @author xwl
 */
public enum SceneTypeEnums {
    SCENE_TYPE_1(1, "一般行业"),
    SCENE_TYPE_11(11, "维耶里"),

    SCENE_TYPE_2(2, "电解槽行业"),
    SCENE_TYPE_3(3, "集热器行业"),
    SCENE_TYPE_4(4, "回转窑行业"),
    SCENE_TYPE_5(5, "电力行业"),
    SCENE_TYPE_6(6, "局放报警");


    private final Integer key;
    private final String description;
    private static final Map<Integer, SceneTypeEnums> map = new HashMap<>();

    static {
        for (SceneTypeEnums yn : SceneTypeEnums.values()) {
            map.put(yn.key, yn);
        }
    }

    private SceneTypeEnums(Integer key, String descriptiion) {
        this.key = key;
        this.description = descriptiion;
    }

    public static String getValue(Integer key) {
        return fromKey(key).description;
    }

    public static SceneTypeEnums fromKey(Integer key) {
        if (SceneTypeEnums.map.containsKey(key)) {
            return SceneTypeEnums.map.get(key);
        }
        return null;
    }

    public Integer getKey() {
        return this.key;
    }

    public String getDescription() {
        return this.description;
    }
}
