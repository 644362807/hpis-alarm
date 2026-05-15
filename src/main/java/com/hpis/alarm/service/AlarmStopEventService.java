package com.hpis.alarm.service;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.config.sharding.AlarmCidIndexService;
import com.hpis.alarm.config.sharding.AlarmShardContext;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.domain.AlarmStopEvent;
import com.hpis.alarm.dto.AlarmStopApplyItem;
import com.hpis.alarm.enums.AlarmStatusEnums;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.mapper.AlarmStopEventMapper;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * stop 消息可靠落库和批量关闭服务。
 *
 * <p>MQ stop 消息进入后只做一件核心动作：upsert 到 alarm_stop_event。落库成功后 MQ 才 ack，
 * 因此即使业务线程池满、关闭 worker 重启或远程同步变慢，stop 也不会丢。后台 worker
 * 按 table_suffix 分组批量更新业务分片，再关闭 hot/stale cid 路由，最后把 stop event
 * 标记为 APPLIED 并设置短期 delete_after。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmStopEventService {

    private final AlarmStopEventMapper stopEventMapper;
    private final AlarmMapper alarmMapper;
    private final AlarmCidIndexService alarmCidIndexService;
    private final AlarmStopSideEffectService sideEffectService;
    private final AlarmStopWorkerProperties properties;

    public AlarmStopEventService(AlarmStopEventMapper stopEventMapper,
                                 AlarmMapper alarmMapper,
                                 AlarmCidIndexService alarmCidIndexService,
                                 AlarmStopSideEffectService sideEffectService,
                                 AlarmStopWorkerProperties properties) {
        this.stopEventMapper = stopEventMapper;
        this.alarmMapper = alarmMapper;
        this.alarmCidIndexService = alarmCidIndexService;
        this.sideEffectService = sideEffectService;
        this.properties = properties;
    }

    public void recordStop(JSONObject rawData) {
        String alarmCid = rawData == null ? null : rawData.getString("alarmId");
        if (StringUtils.isBlank(alarmCid)) {
            throw new IllegalArgumentException("stop 消息缺少 alarmId，无法写入 alarm_stop_event");
        }
        Date stopTime = parseStopTime(rawData.getString("time"));
        stopEventMapper.upsertPending(alarmCid, stopTime);
    }

    /**
     * start 写入 hot route 后立即检查是否已经存在同 cid 的 PENDING stop。
     *
     * <p>现场可能出现 stop 先到、start 后到的乱序。因为 stop 已经持久化在 alarm_stop_event，
     * start 成功后可以马上用刚生成的 table_suffix/alarm_id 关闭业务分片，避免结束时间丢失。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean applyPendingStopForNewAlarm(Alarm alarm, AlarmCidRoute route) {
        if (alarm == null || route == null || StringUtils.isBlank(alarm.getAlarmCid())) {
            return false;
        }
        AlarmStopEvent event = stopEventMapper.selectPendingByCid(alarm.getAlarmCid());
        if (event == null) {
            return false;
        }
        StopRouteContext context = buildContext(event, route);
        try {
            applySingleContext(context, alarm);
            return true;
        } catch (Exception ex) {
            markRetryOrFailed(event, ex);
            log.warn("start 后补偿 PENDING stop 失败，保留事件等待 worker 重试，alarmCid={}, error={}",
                    alarm.getAlarmCid(), ex.getMessage(), ex);
            return false;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int processPendingBatch() {
        int pendingCount = countPending();
        int batchSize = pendingCount >= properties.getHighWatermark()
                ? properties.getHighBatchSize()
                : properties.getNormalBatchSize();
        List<AlarmStopEvent> events = stopEventMapper.selectPendingBatch(batchSize);
        if (events.isEmpty()) {
            return 0;
        }

        /*
         * 先只做路由和分组，不立即更新业务表。
         *
         * stop event 是按 cid 写入的，但真正更新 alarm 表必须命中具体物理分片。
         * 因此这里先双读 hot/stale active route，拿到 table_suffix 后再按 suffix 聚合，
         * 后面每个分组只设置一次 AlarmShardContext 并批量关闭，避免每条 stop 单独路由和更新。
         */
        Map<String, List<StopRouteContext>> grouped = new LinkedHashMap<>();
        List<Long> alreadyClosedEventIds = new ArrayList<>();
        int applied = 0;
        for (AlarmStopEvent event : events) {
            AlarmCidRoute route = alarmCidIndexService.findActiveRouteByCid(event.getAlarmCid());
            if (route == null) {
                if (isRouteAlreadyClosed(event)) {
                    alreadyClosedEventIds.add(event.getId());
                }
                continue;
            }
            StopRouteContext context = buildContext(event, route);
            grouped.computeIfAbsent(route.getTableSuffix(), key -> new ArrayList<>()).add(context);
        }

        if (!alreadyClosedEventIds.isEmpty()) {
            stopEventMapper.markAppliedBatch(alreadyClosedEventIds, buildDeleteAfter());
            applied += alreadyClosedEventIds.size();
        }

        for (Map.Entry<String, List<StopRouteContext>> entry : grouped.entrySet()) {
            applied += applyRouteGroup(entry.getKey(), entry.getValue());
        }
        return applied;
    }

    public int cleanupAppliedIfAllowed() {
        if (properties.isCleanupOnlyLowTraffic() && !isLowTraffic()) {
            if (properties.isLogEnabled()) {
                log.info("当前不是低流量模式，暂停清理 alarm_stop_event APPLIED 记录，pending={}", countPending());
            }
            return 0;
        }
        return stopEventMapper.deleteApplied(DateUtils.getNowDate(), properties.getCleanupBatchSize());
    }

    public boolean isLowTraffic() {
        return countPending() <= properties.getLowWatermark();
    }

    public boolean isHighTraffic() {
        return countPending() >= properties.getHighWatermark();
    }

    public int countPending() {
        Integer count = stopEventMapper.countPending();
        return count == null ? 0 : count;
    }

    public int currentBatchLoops() {
        return isHighTraffic() ? Math.max(1, properties.getMaxParallelism()) : 1;
    }

    private int applyRouteGroup(String tableSuffix, List<StopRouteContext> contexts) {
        if (contexts.isEmpty()) {
            return 0;
        }
        try {
            /*
             * 同一个 tableSuffix 下的报警会落在同一组物理表，可以安全批量更新。
             * 顺序保持为：先写业务 alarm_endTime，再关闭 cid route，最后标记 stop event APPLIED。
             * 如果业务表更新或 route 关闭失败，事务会回滚，stop event 继续保持 PENDING 等待重试。
             */
            AlarmShardContext.setTableSuffix(tableSuffix);
            List<AlarmStopApplyItem> items = contexts.stream()
                    .map(StopRouteContext::getItem)
                    .collect(Collectors.toList());
            alarmMapper.batchStopByAlarmIds(items, AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());

            List<Long> alarmIds = items.stream()
                    .map(AlarmStopApplyItem::getAlarmId)
                    .collect(Collectors.toList());
            Map<Long, Alarm> alarmMap = alarmMapper.selectAlarmByIdsForStop(alarmIds).stream()
                    .collect(Collectors.toMap(Alarm::getAlarmId, alarm -> alarm, (left, right) -> left));

            /*
             * 批量更新后再补查业务报警，是为了拿到 sceneType、targetName 等生成 side effect
             * 所需字段。缺失业务报警的 event 不标记 APPLIED，而是进入 retry/failed，
             * 避免 route 关闭了但业务数据并未真正确认的情况。
             */
            List<StopRouteContext> validContexts = new ArrayList<>();
            for (StopRouteContext context : contexts) {
                Alarm alarm = alarmMap.get(context.getRoute().getAlarmId());
                if (alarm == null) {
                    markRetryOrFailed(context.getEvent(), new IllegalStateException(
                            "业务分片未查询到报警，alarmId=" + context.getRoute().getAlarmId()));
                    continue;
                }
                context.setAlarm(alarm);
                validContexts.add(context);
            }
            Date deleteAfter = buildDeleteAfter();
            List<AlarmStopApplyItem> hotItems = new ArrayList<>();
            List<AlarmStopApplyItem> staleItems = new ArrayList<>();
            List<Long> eventIds = new ArrayList<>();
            for (StopRouteContext context : validContexts) {
                if (AlarmCidRoute.SOURCE_STALE.equals(context.getRoute().getIndexSource())) {
                    staleItems.add(context.getItem());
                } else {
                    hotItems.add(context.getItem());
                }
                eventIds.add(context.getEvent().getId());
            }
            if (!validContexts.isEmpty()) {
                alarmCidIndexService.closeRoutesByItems(hotItems, staleItems, deleteAfter);
                stopEventMapper.markAppliedBatch(eventIds, deleteAfter);
            }
            /*
             * side effect 只生成事件，不参与核心消警成功判定。
             * 这样远程设备恢复、电解槽扩展清理、推送等慢动作不会拖住 alarm_endTime 写入。
             */
            for (StopRouteContext context : validContexts) {
                try {
                    sideEffectService.createEvents(context.getAlarm(), context.getRoute());
                } catch (Exception ex) {
                    log.error("生成消警副作用事件失败，不影响核心消警结果，alarmId={}, alarmCid={}, error={}",
                            context.getRoute().getAlarmId(), context.getRoute().getAlarmCid(), ex.getMessage(), ex);
                }
            }
            return validContexts.size();
        } finally {
            AlarmShardContext.clear();
        }
    }

    private void applySingleContext(StopRouteContext context, Alarm alarm) {
        try {
            AlarmShardContext.setTableSuffix(context.getRoute().getTableSuffix());
            alarmMapper.batchStopByAlarmIds(Collections.singletonList(context.getItem()),
                    AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
            alarm.setAlarmEndtime(context.getEvent().getStopTime());
            applyRouteAndEvent(context, alarm);
        } finally {
            AlarmShardContext.clear();
        }
    }

    private void applyRouteAndEvent(StopRouteContext context, Alarm alarm) {
        alarmCidIndexService.closeRoute(context.getRoute(), context.getEvent().getStopTime());
        stopEventMapper.markApplied(context.getEvent().getId(), buildDeleteAfter());
        try {
            sideEffectService.createEvents(alarm, context.getRoute());
        } catch (Exception ex) {
            log.error("生成消警副作用事件失败，不影响核心消警结果，alarmId={}, alarmCid={}, error={}",
                    alarm.getAlarmId(), alarm.getAlarmCid(), ex.getMessage(), ex);
        }
    }

    private boolean isRouteAlreadyClosed(AlarmStopEvent event) {
        /*
         * 重复 stop 或接口重试时，active route 可能已经不存在。
         * 如果还能在 hot/stale 中查到 CLOSED route，说明核心消警已完成，此时直接把 event
         * 批量标记 APPLIED 即可；如果完全查不到，则不扫历史分片，继续保留 PENDING 等 start 补偿。
         */
        AlarmCidRoute existingRoute = alarmCidIndexService.findRouteByCid(event.getAlarmCid());
        if (existingRoute != null && AlarmCidRoute.STATUS_CLOSED.equals(existingRoute.getRouteStatus())) {
            return true;
        }
        log.debug("stop event 暂未找到 ACTIVE 路由，保留 PENDING 等待 start 补偿，alarmCid={}", event.getAlarmCid());
        return false;
    }

    private StopRouteContext buildContext(AlarmStopEvent event, AlarmCidRoute route) {
        AlarmStopApplyItem item = new AlarmStopApplyItem();
        item.setEventId(event.getId());
        item.setAlarmId(route.getAlarmId());
        item.setAlarmCid(route.getAlarmCid());
        item.setStopTime(event.getStopTime());

        StopRouteContext context = new StopRouteContext();
        context.setEvent(event);
        context.setRoute(route);
        context.setItem(item);
        return context;
    }

    private Date parseStopTime(String stopTimeText) {
        if (StringUtils.isBlank(stopTimeText)) {
            return DateUtils.getNowDate();
        }
        try {
            return DateUtil.parse(stopTimeText);
        } catch (Exception ex) {
            log.warn("stop 消息结束时间解析失败，使用当前时间，stopTime={}", stopTimeText, ex);
            return DateUtils.getNowDate();
        }
    }

    private Date buildDeleteAfter() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(DateUtils.getNowDate());
        calendar.add(Calendar.MINUTE, properties.getAppliedRetentionMinutes());
        return calendar.getTime();
    }

    private void markRetryOrFailed(AlarmStopEvent event, Exception ex) {
        String error = truncateError(ex);
        int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
        if (retryCount + 1 >= properties.getMaxRetry()) {
            stopEventMapper.markFailed(event.getId(), error);
            log.error("stop event 达到最大重试次数，eventId={}, alarmCid={}, error={}",
                    event.getId(), event.getAlarmCid(), error);
        } else {
            stopEventMapper.markRetry(event.getId(), error);
            log.warn("stop event 处理失败，等待重试，eventId={}, alarmCid={}, error={}",
                    event.getId(), event.getAlarmCid(), error);
        }
    }

    private String truncateError(Exception ex) {
        String message = ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    @Data
    private static class StopRouteContext {
        private AlarmStopEvent event;
        private AlarmCidRoute route;
        private AlarmStopApplyItem item;
        private Alarm alarm;
    }
}
