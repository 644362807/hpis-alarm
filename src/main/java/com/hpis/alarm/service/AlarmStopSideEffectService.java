package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.domain.AlarmStopSideEffectEvent;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.mapper.AlarmStopEventMapper;
import com.hpis.alarm.mapper.AlarmStopSideEffectMapper;
import com.hpis.common.core.enums.IrTypeEnums;
import com.hpis.common.core.enums.UserStatus;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.device.api.RemoteIrChannelService;
import com.hpis.device.api.RemoteTmService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 消警副作用服务。
 *
 * <p>结束报警后原逻辑会同步调用设备状态恢复、扩展表清理等动作。高流量场景下这些动作
 * 会放大 stop 堵塞，因此本服务把它们拆成独立事件：核心 worker 只负责生成事件，
 * side effect worker 再独立执行和重试。即使远程服务暂时不可用，也不会影响 alarm_endTime
 * 和 cid 路由关闭成功。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmStopSideEffectService {

    private final AlarmStopSideEffectMapper sideEffectMapper;
    private final AlarmStopEventMapper stopEventMapper;
    private final AlarmStopWorkerProperties properties;
    private final RemoteIrChannelService remoteIrChannelService;
    private final RemoteTmService remoteTmService;
    private final IAlarmElectrolyticCellService alarmElectrolyticCellService;

    public AlarmStopSideEffectService(AlarmStopSideEffectMapper sideEffectMapper,
                                      AlarmStopEventMapper stopEventMapper,
                                      AlarmStopWorkerProperties properties,
                                      RemoteIrChannelService remoteIrChannelService,
                                      RemoteTmService remoteTmService,
                                      IAlarmElectrolyticCellService alarmElectrolyticCellService) {
        this.sideEffectMapper = sideEffectMapper;
        this.stopEventMapper = stopEventMapper;
        this.properties = properties;
        this.remoteIrChannelService = remoteIrChannelService;
        this.remoteTmService = remoteTmService;
        this.alarmElectrolyticCellService = alarmElectrolyticCellService;
    }

    public void createEvents(Alarm alarm, AlarmCidRoute route) {
        if (!properties.isSideEffectEnabled() || alarm == null || route == null || alarm.getAlarmId() == null) {
            return;
        }
        createOfflineRecoverEventIfNecessary(alarm, route);
        createEcCleanupEvent(alarm, route);
    }

    public int processPendingBatch() {
        if (!properties.isSideEffectEnabled()) {
            return 0;
        }
        int pendingStopCount = stopEventMapper.countPending();
        if (pendingStopCount >= properties.getHighWatermark()) {
            log.info("消警高流量模式下暂停执行副作用事件，pendingStopCount={}", pendingStopCount);
            return 0;
        }
        List<AlarmStopSideEffectEvent> events = sideEffectMapper.selectPendingBatch(properties.getNormalBatchSize());
        int done = 0;
        for (AlarmStopSideEffectEvent event : events) {
            try {
                execute(event);
                sideEffectMapper.markDone(event.getId());
                done++;
            } catch (Exception ex) {
                markRetryOrFailed(event, ex);
            }
        }
        return done;
    }

    private void createOfflineRecoverEventIfNecessary(Alarm alarm, AlarmCidRoute route) {
        if (!AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription().equals(alarm.getAlarmType())) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("deviceSn", alarm.getDeviceSn());
        payload.put("isActive", UserStatus.OK.getCode());
        String effectType = null;
        if (StringUtils.equals(alarm.getTargetName(), IrTypeEnums.ITEMS_0.getDescription())) {
            payload.put("cameraType", IrTypeEnums.ITEMS_0.getKey());
            effectType = AlarmStopSideEffectEvent.EFFECT_IR_OFFLINE_RECOVER;
        } else if (StringUtils.equals(alarm.getTargetName(), IrTypeEnums.ITEMS_1.getDescription())) {
            payload.put("cameraType", IrTypeEnums.ITEMS_1.getKey());
            effectType = AlarmStopSideEffectEvent.EFFECT_IR_OFFLINE_RECOVER;
        } else if (StringUtils.equals(alarm.getTargetName(), IrTypeEnums.ITEMS_10.getDescription())) {
            effectType = AlarmStopSideEffectEvent.EFFECT_TM_OFFLINE_RECOVER;
        }
        if (effectType != null) {
            upsertEvent(alarm, route, effectType, payload);
        }
    }

    private void createEcCleanupEvent(Alarm alarm, AlarmCidRoute route) {
        JSONObject payload = new JSONObject();
        payload.put("alarmId", alarm.getAlarmId());
        upsertEvent(alarm, route, AlarmStopSideEffectEvent.EFFECT_EC_ECTYPE_DELETE, payload);
    }

    private void upsertEvent(Alarm alarm, AlarmCidRoute route, String effectType, JSONObject payload) {
        AlarmStopSideEffectEvent event = new AlarmStopSideEffectEvent();
        event.setAlarmId(alarm.getAlarmId());
        event.setAlarmCid(alarm.getAlarmCid());
        event.setTableSuffix(route.getTableSuffix());
        event.setEffectType(effectType);
        event.setPayloadJson(payload.toJSONString());
        sideEffectMapper.upsertPending(event);
    }

    private void execute(AlarmStopSideEffectEvent event) {
        JSONObject payload = JSONObject.parseObject(event.getPayloadJson());
        if (AlarmStopSideEffectEvent.EFFECT_IR_OFFLINE_RECOVER.equals(event.getEffectType())) {
            remoteIrChannelService.alarmIrOffLine(payload);
        } else if (AlarmStopSideEffectEvent.EFFECT_TM_OFFLINE_RECOVER.equals(event.getEffectType())) {
            remoteTmService.alarmTmOffLine(payload);
        } else if (AlarmStopSideEffectEvent.EFFECT_EC_ECTYPE_DELETE.equals(event.getEffectType())) {
            alarmElectrolyticCellService.deleteAlarmElectrolyticCellEctypeById(payload.getLong("alarmId"));
        } else {
            throw new IllegalArgumentException("未知消警副作用类型: " + event.getEffectType());
        }
    }

    private void markRetryOrFailed(AlarmStopSideEffectEvent event, Exception ex) {
        String error = truncateError(ex);
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        if (retryCount + 1 >= properties.getMaxRetry()) {
            sideEffectMapper.markFailed(event.getId(), error);
            log.error("消警副作用事件达到最大重试次数，eventId={}, effectType={}, error={}",
                    event.getId(), event.getEffectType(), error);
        } else {
            sideEffectMapper.markRetry(event.getId(), error);
            log.warn("消警副作用事件执行失败，等待重试，eventId={}, effectType={}, error={}",
                    event.getId(), event.getEffectType(), error);
        }
    }

    private String truncateError(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
