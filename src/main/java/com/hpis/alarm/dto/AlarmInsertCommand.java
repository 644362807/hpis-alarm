package com.hpis.alarm.dto;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.enums.SceneTypeEnums;
import lombok.Data;

/**
 * 报警新增链路的内部命令对象。
 *
 * <p>第一阶段重构仍保留 {@code AlarmServiceImpl.insertAlarm(JSONObject)} 作为外部入口，
 * 这里只把设备解析、断线去重需要的字段从 {@code JSONObject} 中集中提取出来。
 * 它不是完整报警领域模型，也不改变 push 消息体、DB 字段或 MQ 协议。</p>
 */
@Data
public class AlarmInsertCommand {

    private String alarmCid;
    private Integer sceneType;
    private Integer srcSceneType;
    private String deviceSn;
    private String alarmType;
    private String cameraType;
    private JSONObject pdData;

    /**
     * 在 {@code sceneTypeDataHandle} 完成后创建命令对象。
     *
     * <p>此时 {@code sceneType} 可能已经从真实行业转换为逻辑行业，
     * {@code srcSceneType} 保留原始行业，供维也里等特殊来源判断使用。</p>
     */
    public static AlarmInsertCommand from(JSONObject jsonObject) {
        AlarmInsertCommand command = new AlarmInsertCommand();
        command.setAlarmCid(jsonObject.getString("alarmId"));
        command.setSceneType(jsonObject.getInteger("sceneType"));
        command.setSrcSceneType(jsonObject.getInteger("srcSceneType"));
        command.setDeviceSn(jsonObject.getString("deviceSn"));
        command.setAlarmType(jsonObject.getString("alarmType"));
        command.setCameraType(jsonObject.getString("cameraType"));
        command.setPdData(jsonObject.getJSONObject("pdData"));
        return command;
    }

    /**
     * 是否为断线报警；当前只有断线报警会走 Redis SETNX 去重。
     */
    public boolean isDisconnectAlarm() {
        return AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey().equals(alarmType);
    }

    /**
     * 是否为局放报警；局放设备缓存 key 需要拼接 {@code pdData.sensorId}。
     */
    public boolean isPartialDischarge() {
        return sceneType != null && sceneType.intValue() == SceneTypeEnums.SCENE_TYPE_6.getKey();
    }

    /**
     * 是否来源于维也里逻辑行业；温度传感器设备 sn 可能是逗号拼接，需要取第一个 sn 查缓存。
     */
    public boolean isVieriSource() {
        return srcSceneType != null && srcSceneType.intValue() == SceneTypeEnums.SCENE_TYPE_11.getKey();
    }
}
