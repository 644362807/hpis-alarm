package com.hpis.alarm.domain;

import lombok.Data;

import java.util.Date;

/**
 * 消警后的非核心副作用事件。
 *
 * <p>核心消警只负责写 alarm_endTime 和关闭 cid 路由；设备状态恢复、电解槽扩展表清理、
 * 推送等动作都记录到本表异步执行。这样远程服务慢、失败或超时，不会阻塞 stop event
 * 从 PENDING 变成 APPLIED，也不会继续拖慢新的报警写入。</p>
 */
@Data
public class AlarmStopSideEffectEvent {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";

    public static final String EFFECT_IR_OFFLINE_RECOVER = "IR_OFFLINE_RECOVER";
    public static final String EFFECT_TM_OFFLINE_RECOVER = "TM_OFFLINE_RECOVER";
    public static final String EFFECT_EC_ECTYPE_DELETE = "EC_ECTYPE_DELETE";
    public static final String EFFECT_PUSH_NOTIFY = "PUSH_NOTIFY";

    private Long id;

    private Long alarmId;

    private String alarmCid;

    private String tableSuffix;

    private String effectType;

    /** JSON 字符串，保存执行副作用所需的最小上下文。 */
    private String payloadJson;

    private String effectStatus;

    private Integer retryCount;

    private String lastError;

    private Date createdTime;

    private Date updatedTime;
}
