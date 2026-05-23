package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.domain.AlarmStopSideEffectEvent;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.enums.SceneTypeEnums;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final AlarmBatchProperties batchProperties;
    private final AlarmStopWorkerProperties properties;
    private final RemoteIrChannelService remoteIrChannelService;
    private final RemoteTmService remoteTmService;
    private final IAlarmElectrolyticCellService alarmElectrolyticCellService;

    public AlarmStopSideEffectService(AlarmStopSideEffectMapper sideEffectMapper,
                                      AlarmStopEventMapper stopEventMapper,
                                      AlarmBatchProperties batchProperties,
                                      AlarmStopWorkerProperties properties,
                                      RemoteIrChannelService remoteIrChannelService,
                                      RemoteTmService remoteTmService,
                                      IAlarmElectrolyticCellService alarmElectrolyticCellService) {
        this.sideEffectMapper = sideEffectMapper;
        this.stopEventMapper = stopEventMapper;
        this.batchProperties = batchProperties;
        this.properties = properties;
        this.remoteIrChannelService = remoteIrChannelService;
        this.remoteTmService = remoteTmService;
        this.alarmElectrolyticCellService = alarmElectrolyticCellService;
    }

    public void createEvents(Alarm alarm, AlarmCidRoute route) {
        /*
         * 这里只负责把需要执行的副作用写成事件，不直接执行远程调用。
         * 核心消警 worker 在业务表和 cid route 已关闭后调用本方法，因此即使后续副作用失败，
         * 也不会影响 stop event 从 PENDING 进入 APPLIED。
         */
        if (!properties.isSideEffectEnabled() || alarm == null || route == null || alarm.getAlarmId() == null) {
            return;
        }
        for (AlarmStopSideEffectEvent event : buildEvents(alarm, route)) {
            sideEffectMapper.upsertPending(event);
        }
    }

    public int createEventsBatch(List<Alarm> alarms, Map<Long, AlarmCidRoute> routeByAlarmId, String batchId) {
        /*
         * 批量生成副作用事件只做 upsertPending，不直接执行远程调用。
         * 这样核心消警事务只需要把“后续要做什么”可靠落库，真正慢调用交给 side effect worker 重试。
         */
        if (!properties.isSideEffectEnabled() || alarms == null || alarms.isEmpty()) {
            return 0;
        }
        List<AlarmStopSideEffectEvent> events = new ArrayList<>();
        for (Alarm alarm : alarms) {
            if (alarm == null || alarm.getAlarmId() == null) {
                continue;
            }
            AlarmCidRoute route = routeByAlarmId == null ? null : routeByAlarmId.get(alarm.getAlarmId());
            events.addAll(buildEvents(alarm, route));
        }
        if (events.isEmpty()) {
            return 0;
        }
        int inLimit = batchProperties.safeInLimit();
        // upsert 也按 inLimit 分片，避免一次批量过大影响 stop 核心事务提交时间。
        for (int start = 0; start < events.size(); start += inLimit) {
            List<AlarmStopSideEffectEvent> chunk = events.subList(start, Math.min(start + inLimit, events.size()));
            sideEffectMapper.upsertPendingBatch(chunk);
        }
        log.info("alarm stop batch stage=SIDE_EFFECT_BATCH_UPSERT batchId={}, effectCount={}, alarmCount={}",
                batchId, events.size(), alarms.size());
        return events.size();
    }

    public int processPendingBatch() {
        /*
         * 副作用执行动作仍逐条处理，因为不同 effectType 可能调用不同远程服务或本地清理逻辑。
         * 但成功后的状态更新可以合并成 markDoneBatch，避免大批量电解槽消警时产生大量单条 UPDATE。
         */
        if (!properties.isSideEffectEnabled()) {
            return 0;
        }
        int pendingStopCount = stopEventMapper.countPending();
        if (pendingStopCount >= properties.getHighWatermark()) {
            if (properties.isLogEnabled()) {
                log.info("消警高流量模式下暂停执行副作用事件，pendingStopCount={}", pendingStopCount);
            }
            return 0;
        }
        List<AlarmStopSideEffectEvent> events = sideEffectMapper.selectPendingBatch(properties.getNormalBatchSize());
        int done = 0;
        List<Long> doneIds = new ArrayList<>();
        for (AlarmStopSideEffectEvent event : events) {
            try {
                execute(event);
                doneIds.add(event.getId());
                done++;
            } catch (Exception ex) {
                markRetryOrFailed(event, ex);
            }
        }
        if (!doneIds.isEmpty()) {
            sideEffectMapper.markDoneBatch(doneIds);
        }
        return done;
    }

    private List<AlarmStopSideEffectEvent> buildEvents(Alarm alarm, AlarmCidRoute route) {
        List<AlarmStopSideEffectEvent> events = new ArrayList<>();
        if (alarm == null || route == null || alarm.getAlarmId() == null) {
            return events;
        }
        AlarmStopSideEffectEvent offlineRecoverEvent = buildOfflineRecoverEventIfNecessary(alarm, route);
        if (offlineRecoverEvent != null) {
            events.add(offlineRecoverEvent);
        }
        AlarmStopSideEffectEvent ecCleanupEvent = buildEcCleanupEvent(alarm, route);
        if (ecCleanupEvent != null) {
            events.add(ecCleanupEvent);
        }
        return events;
    }

    private AlarmStopSideEffectEvent buildOfflineRecoverEventIfNecessary(Alarm alarm, AlarmCidRoute route) {
        if (!AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription().equals(alarm.getAlarmType())) {
            return null;
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
        return effectType == null ? null : buildEvent(alarm, route, effectType, payload);
    }

    private AlarmStopSideEffectEvent buildEcCleanupEvent(Alarm alarm, AlarmCidRoute route) {
        /*
         * EC 扩展清理只属于电解槽行业。一般行业也可能有温度/断线报警，
         * 如果不校验 sceneType，会误删或误触发电解槽扩展清理事件。
        */
        if (!StringUtils.equals(String.valueOf(SceneTypeEnums.SCENE_TYPE_2.getKey()), alarm.getSceneType())) {
            return null;
        }
        JSONObject payload = new JSONObject();
        payload.put("alarmId", alarm.getAlarmId());
        return buildEvent(alarm, route, AlarmStopSideEffectEvent.EFFECT_EC_ECTYPE_DELETE, payload);
    }

    private AlarmStopSideEffectEvent buildEvent(Alarm alarm, AlarmCidRoute route, String effectType, JSONObject payload) {
        AlarmStopSideEffectEvent event = new AlarmStopSideEffectEvent();
        event.setAlarmId(alarm.getAlarmId());
        event.setAlarmCid(alarm.getAlarmCid());
        event.setTableSuffix(route.getTableSuffix());
        event.setEffectType(effectType);
        event.setPayloadJson(payload.toJSONString());
        return event;
    }

    private void execute(AlarmStopSideEffectEvent event) {
        /*
         * 执行阶段只处理已经持久化的副作用事件。
         * 失败会进入 retry/failed，不反向影响 alarm_endTime 和 route CLOSED 状态。
         */
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
