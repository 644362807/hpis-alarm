package com.hpis.alarm.service.support;

/**
 * 报警新增链路中的设备缓存缺失异常。
 *
 * <p>这个异常只表示“本条报警引用的设备快照在 Redis 中不存在”，不是数据库或 MQ 基础设施故障。
 * 当前业务要求是：MQ 消费时记录该问题并丢弃消息，避免同一条无法修复的数据反复 requeue。
 * 因此 MQ listener 会单独捕获它并 ack；其他调用方可以按自身语义处理该异常。</p>
 */
public class AlarmDeviceCacheMissingException extends RuntimeException {

    private final String alarmCid;
    private final String deviceSn;
    private final Integer sceneType;
    private final String cameraType;

    public AlarmDeviceCacheMissingException(String alarmCid, String deviceSn, Integer sceneType, String cameraType) {
        super("Device cache not found, alarmCid=" + alarmCid
                + ", deviceSn=" + deviceSn
                + ", sceneType=" + sceneType
                + ", cameraType=" + cameraType);
        this.alarmCid = alarmCid;
        this.deviceSn = deviceSn;
        this.sceneType = sceneType;
        this.cameraType = cameraType;
    }

    public String getAlarmCid() {
        return alarmCid;
    }

    public String getDeviceSn() {
        return deviceSn;
    }

    public Integer getSceneType() {
        return sceneType;
    }

    public String getCameraType() {
        return cameraType;
    }
}
