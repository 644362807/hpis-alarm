package com.hpis.alarm.service;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmBatchProperties;
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
import com.hpis.alarm.task.AlarmStopWorkerSignal;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    private static final String ROUTE_MISSING_ERROR = "ROUTE_MISSING";

    private final AlarmStopEventMapper stopEventMapper;
    private final AlarmMapper alarmMapper;
    private final AlarmCidIndexService alarmCidIndexService;
    private final AlarmStopSideEffectService sideEffectService;
    private final AlarmBatchProperties batchProperties;
    private final AlarmStopWorkerProperties properties;
    private final AlarmStopWorkerSignal workerSignal;

    public AlarmStopEventService(AlarmStopEventMapper stopEventMapper,
                                 AlarmMapper alarmMapper,
                                 AlarmCidIndexService alarmCidIndexService,
                                 AlarmStopSideEffectService sideEffectService,
                                 AlarmBatchProperties batchProperties,
                                 AlarmStopWorkerProperties properties,
                                 AlarmStopWorkerSignal workerSignal) {
        this.stopEventMapper = stopEventMapper;
        this.alarmMapper = alarmMapper;
        this.alarmCidIndexService = alarmCidIndexService;
        this.sideEffectService = sideEffectService;
        this.batchProperties = batchProperties;
        this.properties = properties;
        this.workerSignal = workerSignal;
    }

    public void recordStop(JSONObject rawData) {
        /*
         * MQ stop 的可靠入口只做 upsert PENDING。
         * 只要这里成功，listener 就可以 ack；真正关闭业务表由 worker 重试完成，避免 MQ 消息丢失。
         */
        String alarmCid = rawData == null ? null : rawData.getString("alarmId");
        if (StringUtils.isBlank(alarmCid)) {
            throw new IllegalArgumentException("stop 消息缺少 alarmId，无法写入 alarm_stop_event");
        }
        Date stopTime = parseStopTime(rawData.getString("time"));
        stopEventMapper.upsertPending(alarmCid, stopTime);
        wakeWorkerAfterCommit("recordStop", alarmCid);
    }

    private void wakeWorkerAfterCommit(final String reason, final String alarmCid) {
        /*
         * 如果 recordStop 处在事务里，必须 afterCommit 再唤醒。
         * 否则 worker 可能先扫库但看不到未提交的 PENDING，随后进入 idle，造成额外延迟。
         */
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    workerSignal.wakeUp(reason, alarmCid);
                }
            });
            return;
        }
        workerSignal.wakeUp(reason, alarmCid);
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
        AlarmStopEvent event = stopEventMapper.selectRecoverableByCid(alarm.getAlarmCid());
        if (event == null) {
            return false;
        }
        StopRouteContext context = buildContext(event, route);
        try {
            applySingleContext(context, alarm);
            wakeWorkerAfterCommit("applyPendingStopForNewAlarm", alarm.getAlarmCid());
            return true;
        } catch (Exception ex) {
            markRetryOrFailed(event, ex);
            log.warn("start 后补偿 PENDING stop 失败，保留事件等待 worker 重试，alarmCid={}, error={}",
                    alarm.getAlarmCid(), ex.getMessage(), ex);
            return false;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int applyPendingStopsForNewAlarms(List<Alarm> alarms, Map<String, AlarmCidRoute> routeByCid) {
        /*
         * 批量 insert 成功后处理 stop 早到场景。
         * 这里复用本次 insert 已经生成的 routeByCid，不再逐条查 route，避免 start 批量落库后立刻出现 N 次 route 查询。
         */
        if (alarms == null || alarms.isEmpty() || routeByCid == null || routeByCid.isEmpty()) {
            return 0;
        }
        List<String> alarmCids = alarms.stream()
                .map(Alarm::getAlarmCid)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        if (alarmCids.isEmpty()) {
            return 0;
        }
        String batchId = UUID.randomUUID().toString().replace("-", "");
        int applied = 0;
        for (List<String> chunk : chunkStrings(alarmCids, batchProperties.safeInLimit())) {
            List<AlarmStopEvent> events = stopEventMapper.selectRecoverableByCids(chunk);
            Map<String, List<StopRouteContext>> grouped = new LinkedHashMap<>();
            for (AlarmStopEvent event : events) {
                AlarmCidRoute route = routeByCid.get(event.getAlarmCid());
                if (route != null) {
                    grouped.computeIfAbsent(route.getTableSuffix(), key -> new ArrayList<>())
                            .add(buildContext(event, route));
                }
            }
            for (Map.Entry<String, List<StopRouteContext>> entry : grouped.entrySet()) {
                applied += applyRouteGroup(batchId, entry.getKey(), entry.getValue());
            }
        }
        if (applied > 0) {
            log.info("alarm insert batch stage=BATCH_PENDING_STOP_APPLY batchId={}, appliedCount={}, sampleAlarmCids={}",
                    batchId, applied, alarmCids.stream().limit(5).collect(Collectors.toList()));
            wakeWorkerAfterCommit("applyPendingStopsForNewAlarms", null);
        }
        return applied;
    }

    @Transactional(rollbackFor = Exception.class)
    public int processPendingBatch() {
        /*
         * worker 每轮只取一批 PENDING。
         * 高水位时自动放大 batchSize，低水位时保持较小批量，避免空闲期占用过多 DB 资源。
         */
        int pendingCount = countPending();
        int batchSize = pendingCount >= properties.getHighWatermark()
                ? properties.getHighBatchSize()
                : properties.getNormalBatchSize();
        List<AlarmStopEvent> events = stopEventMapper.selectPendingBatch(batchSize);
        if (events == null || events.isEmpty()) {
            return recoverFailedRouteMissingBatch();
        }
        if (properties.isLogEnabled()) {
            log.info("alarm stop worker stage=PENDING_BATCH_PLAN pendingCount={}, batchSize={}, highTraffic={}",
                    pendingCount, batchSize, pendingCount >= properties.getHighWatermark());
        }
        if (batchProperties.isStopEnabled()) {
            return processPendingBatchBulk(events);
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
            applied += applyRouteGroup("legacy", entry.getKey(), entry.getValue());
        }
        return applied;
    }

    private int processPendingBatchBulk(List<AlarmStopEvent> events) {
        /*
         * 批量链路按 alarm.batch.inLimit 切 chunk。
         * route 查询异常属于批量查询层异常，可以按配置拆回单条；但 route 真缺失会直接 FAILED，不再回到旧 PENDING 等待。
         */
        String batchId = UUID.randomUUID().toString().replace("-", "");
        int processed = 0;
        int inLimit = batchProperties.safeInLimit();
        for (List<AlarmStopEvent> chunk : chunk(events, inLimit)) {
            long startMs = System.currentTimeMillis();
            try {
                processed += processPendingChunk(batchId, events.size(), chunk);
            } catch (RouteLookupFailedException ex) {
                if (!batchProperties.isFallbackSingleOnBatchError()) {
                    throw ex;
                }
                log.warn("alarm stop batch stage=FALLBACK_SINGLE batchId={}, chunkSize={}, sampleAlarmCids={}, costMs={}, error={}",
                        batchId, chunk.size(), sampleCids(chunk), System.currentTimeMillis() - startMs, ex.getMessage(), ex);
                processed += processPendingChunkSingleFallback(batchId, chunk);
            }
        }
        return processed;
    }

    private int processPendingChunk(String batchId, int batchSize, List<AlarmStopEvent> events) {
        /*
         * 单个 chunk 的处理顺序：
         * 1. 批量查询 ACTIVE route；
         * 2. 对未命中 ACTIVE 的 cid 再查 hot/stale 全状态 route；
         * 3. ACTIVE 按 suffix 分组批量关闭；
         * 4. CLOSED 视为重复 stop 幂等成功；
         * 5. 完全查不到 route 的 event 先记录 ROUTE_MISSING 并保留 PENDING，超过重试上限才 FAILED。
         */
        long startMs = System.currentTimeMillis();
        List<String> alarmCids = events.stream()
                .map(AlarmStopEvent::getAlarmCid)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
        RouteLookupResult routeLookup = findRoutesForChunk(alarmCids);
        Map<String, AlarmCidRoute> activeRouteMap = routeLookup.getActiveRouteMap();
        Map<String, AlarmCidRoute> existingRouteMap = routeLookup.getExistingRouteMap();
        log.info("alarm stop batch stage=BATCH_ROUTE_QUERY batchId={}, batchSize={}, chunkSize={}, activeRouteCount={}, routeCheckCount={}, sampleAlarmCids={}, costMs={}",
                batchId, batchSize, events.size(), activeRouteMap.size(), existingRouteMap.size(),
                sampleCids(events), System.currentTimeMillis() - startMs);

        Map<String, List<StopRouteContext>> grouped = new LinkedHashMap<>();
        List<Long> alreadyClosedEventIds = new ArrayList<>();
        List<AlarmStopEvent> missingRouteEvents = new ArrayList<>();
        for (AlarmStopEvent event : events) {
            AlarmCidRoute activeRoute = activeRouteMap.get(event.getAlarmCid());
            if (activeRoute != null) {
                StopRouteContext context = buildContext(event, activeRoute);
                grouped.computeIfAbsent(activeRoute.getTableSuffix(), key -> new ArrayList<>()).add(context);
                continue;
            }
            AlarmCidRoute existingRoute = existingRouteMap.get(event.getAlarmCid());
            if (existingRoute != null && AlarmCidRoute.STATUS_CLOSED.equals(existingRoute.getRouteStatus())) {
                alreadyClosedEventIds.add(event.getId());
            } else {
                missingRouteEvents.add(event);
            }
        }

        int processed = 0;
        if (!alreadyClosedEventIds.isEmpty()) {
            stopEventMapper.markAppliedBatch(alreadyClosedEventIds, buildDeleteAfter());
            processed += alreadyClosedEventIds.size();
        }
        if (!missingRouteEvents.isEmpty()) {
            processed += markRouteMissingRetryOrFailed(batchId, missingRouteEvents);
        }
        for (Map.Entry<String, List<StopRouteContext>> entry : grouped.entrySet()) {
            processed += applyRouteGroup(batchId, entry.getKey(), entry.getValue());
        }
        return processed;
    }

    private RouteLookupResult findRoutesForChunk(List<String> alarmCids) {
        try {
            // 第一把只查 ACTIVE route，用于真正执行业务分片关闭。
            Map<String, AlarmCidRoute> activeRouteMap =
                    alarmCidIndexService.findActiveRoutesByCids(alarmCids, batchProperties.safeInLimit());
            List<String> inactiveCids = alarmCids.stream()
                    .filter(alarmCid -> !activeRouteMap.containsKey(alarmCid))
                    .collect(Collectors.toList());
            // 第二把查全状态 route，用于识别 CLOSED 幂等成功和 ROUTE_MISSING 真缺失。
            Map<String, AlarmCidRoute> existingRouteMap =
                    alarmCidIndexService.findRoutesByCids(inactiveCids, batchProperties.safeInLimit());
            RouteLookupResult result = new RouteLookupResult();
            result.setActiveRouteMap(activeRouteMap);
            result.setExistingRouteMap(existingRouteMap);
            return result;
        } catch (Exception ex) {
            throw new RouteLookupFailedException(ex);
        }
    }

    private int processPendingChunkSingleFallback(String batchId, List<AlarmStopEvent> events) {
        /*
         * 只有批量 route 查询异常时才走这里。
         * 查不到 hot/stale route 时先记录 ROUTE_MISSING 并保留 PENDING，等待 start 后到补偿。
         */
        int processed = 0;
        for (AlarmStopEvent event : events) {
            AlarmCidRoute route = alarmCidIndexService.findActiveRouteByCid(event.getAlarmCid());
            if (route == null) {
                AlarmCidRoute existingRoute = alarmCidIndexService.findRouteByCid(event.getAlarmCid());
                if (existingRoute != null && AlarmCidRoute.STATUS_CLOSED.equals(existingRoute.getRouteStatus())) {
                    stopEventMapper.markApplied(event.getId(), buildDeleteAfter());
                    processed++;
                } else {
                    processed += markRouteMissingRetryOrFailed(batchId, Collections.singletonList(event));
                }
                continue;
            }
            processed += applyRouteGroup(batchId, route.getTableSuffix(),
                    Collections.singletonList(buildContext(event, route)));
        }
        return processed;
    }

    private int recoverFailedRouteMissingBatch() {
        /*
         * 兼容历史误失败：如果 ROUTE_MISSING 已经 FAILED，但 route 后来出现，仍应恢复关闭。
         * 该扫描只在无 PENDING 时执行，避免高流量主链路额外扫 FAILED 数据。
         */
        int limit = Math.max(1, properties.getRouteMissingRecoveryBatchSize());
        long delayMs = Math.max(0L, properties.getRouteMissingRecoveryDelayMs());
        Date recoverAfter = new Date(System.currentTimeMillis() - delayMs);
        List<AlarmStopEvent> events = stopEventMapper.selectFailedRouteMissingBatch(limit, recoverAfter);
        if (events == null || events.isEmpty()) {
            return 0;
        }
        String batchId = "recover-" + UUID.randomUUID().toString().replace("-", "");
        long startMs = System.currentTimeMillis();
        RouteLookupResult routeLookup = findRoutesForChunk(events.stream()
                .map(AlarmStopEvent::getAlarmCid)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList()));
        Map<String, List<StopRouteContext>> grouped = new LinkedHashMap<>();
        List<Long> alreadyClosedEventIds = new ArrayList<>();
        int missingCount = 0;
        for (AlarmStopEvent event : events) {
            AlarmCidRoute activeRoute = routeLookup.getActiveRouteMap().get(event.getAlarmCid());
            if (activeRoute != null) {
                grouped.computeIfAbsent(activeRoute.getTableSuffix(), key -> new ArrayList<>())
                        .add(buildContext(event, activeRoute));
                continue;
            }
            AlarmCidRoute existingRoute = routeLookup.getExistingRouteMap().get(event.getAlarmCid());
            if (existingRoute != null && AlarmCidRoute.STATUS_CLOSED.equals(existingRoute.getRouteStatus())) {
                alreadyClosedEventIds.add(event.getId());
            } else {
                missingCount++;
            }
        }

        int recovered = 0;
        if (!alreadyClosedEventIds.isEmpty()) {
            stopEventMapper.markAppliedBatch(alreadyClosedEventIds, buildDeleteAfter());
            recovered += alreadyClosedEventIds.size();
        }
        for (Map.Entry<String, List<StopRouteContext>> entry : grouped.entrySet()) {
            recovered += applyRouteGroup(batchId, entry.getKey(), entry.getValue());
        }
        if (recovered > 0 || properties.isLogEnabled()) {
            log.info("alarm stop batch stage=ROUTE_MISSING_RECOVER batchId={}, checkedCount={}, recoveredCount={}, stillMissingCount={}, costMs={}, sampleAlarmCids={}",
                    batchId, events.size(), recovered, missingCount, System.currentTimeMillis() - startMs, sampleCids(events));
        }
        return recovered;
    }

    private int markRouteMissingRetryOrFailed(String batchId, List<AlarmStopEvent> events) {
        List<Long> retryIds = new ArrayList<>();
        List<Long> failedIds = new ArrayList<>();
        for (AlarmStopEvent event : events) {
            int retryCount = event.getRetryCount() == null ? 0 : event.getRetryCount();
            if (retryCount + 1 >= properties.getMaxRetry()) {
                failedIds.add(event.getId());
            } else {
                retryIds.add(event.getId());
            }
        }
        if (!retryIds.isEmpty()) {
            stopEventMapper.markRetryBatch(retryIds, ROUTE_MISSING_ERROR);
            log.warn("alarm stop batch stage=ROUTE_MISSING_RETRY batchId={}, retryCount={}, sampleAlarmCids={}",
                    batchId, retryIds.size(), sampleCids(events));
        }
        if (!failedIds.isEmpty()) {
            stopEventMapper.markFailedBatch(failedIds, ROUTE_MISSING_ERROR);
            log.error("alarm stop batch stage=ROUTE_MISSING_FAILED batchId={}, failedCount={}, sampleAlarmCids={}",
                    batchId, failedIds.size(), sampleCids(events));
        }
        return failedIds.size();
    }

    public int cleanupAppliedIfAllowed() {
        /*
         * APPLIED 清理只影响 stop event 历史表体积，不影响业务报警闭环。
         * 高流量时暂停清理，避免和核心 PENDING 消警争抢数据库资源。
         */
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

    private int applyRouteGroup(String batchId, String tableSuffix, List<StopRouteContext> contexts) {
        if (contexts.isEmpty()) {
            return 0;
        }
        long startMs = System.currentTimeMillis();
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
            long stopUpdateStartMs = System.currentTimeMillis();
            alarmMapper.batchStopByAlarmIds(items, AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
            long stopUpdateMs = System.currentTimeMillis() - stopUpdateStartMs;

            List<Long> alarmIds = items.stream()
                    .map(AlarmStopApplyItem::getAlarmId)
                    .collect(Collectors.toList());
            long selectAlarmStartMs = System.currentTimeMillis();
            Map<Long, Alarm> alarmMap = alarmMapper.selectAlarmByIdsForStop(alarmIds).stream()
                    .collect(Collectors.toMap(Alarm::getAlarmId, alarm -> alarm, (left, right) -> left));
            long selectAlarmMs = System.currentTimeMillis() - selectAlarmStartMs;

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
                long closeRouteStartMs = System.currentTimeMillis();
                alarmCidIndexService.closeRoutesByItems(hotItems, staleItems, deleteAfter);
                long closeRouteMs = System.currentTimeMillis() - closeRouteStartMs;
                long markAppliedStartMs = System.currentTimeMillis();
                stopEventMapper.markAppliedBatch(eventIds, deleteAfter);
                long markAppliedMs = System.currentTimeMillis() - markAppliedStartMs;
                log.info("alarm stop batch stage=BATCH_STOP_CORE batchId={}, tableSuffix={}, chunkSize={}, validCount={}, stopUpdateMs={}, selectAlarmMs={}, closeRouteMs={}, markAppliedMs={}",
                        batchId, tableSuffix, contexts.size(), validContexts.size(),
                        stopUpdateMs, selectAlarmMs, closeRouteMs, markAppliedMs);
            }
            if (!createSideEffectsBatch(batchId, validContexts)) {
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
            }
            log.info("alarm stop batch stage=BATCH_STOP_APPLY batchId={}, tableSuffix={}, chunkSize={}, appliedCount={}, suffixCount=1, costMs={}",
                    batchId, tableSuffix, contexts.size(), validContexts.size(), System.currentTimeMillis() - startMs);
            return validContexts.size();
        } finally {
            AlarmShardContext.clear();
        }
    }

    private boolean createSideEffectsBatch(String batchId, List<StopRouteContext> contexts) {
        /*
         * side effect 生成失败不能回滚核心消警。
         * 失败时返回 false，由调用方拆回单条 createEvents 并记录错误，alarm_endTime 和 route CLOSED 仍保留。
         */
        if (contexts == null || contexts.isEmpty()) {
            return true;
        }
        try {
            Map<Long, AlarmCidRoute> routeByAlarmId = contexts.stream()
                    .collect(Collectors.toMap(context -> context.getRoute().getAlarmId(),
                            StopRouteContext::getRoute, (left, right) -> left));
            List<Alarm> alarms = contexts.stream()
                    .map(StopRouteContext::getAlarm)
                    .collect(Collectors.toList());
            sideEffectService.createEventsBatch(alarms, routeByAlarmId, batchId);
            return true;
        } catch (Exception ex) {
            log.warn("alarm stop batch stage=FALLBACK_SINGLE batchId={}, chunkSize={}, error={}",
                    batchId, contexts.size(), ex.getMessage(), ex);
            return false;
        }
    }

    private void applySingleContext(StopRouteContext context, Alarm alarm) {
        /*
         * 单条路径只用于 start 后补偿或批量异常兜底。
         * 仍然设置 AlarmShardContext，确保只更新 route 指向的物理分片。
         */
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
        // 单条消警顺序同批量链路：先关闭 route，再标记 event APPLIED，副作用失败不反向影响核心结果。
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

    private List<List<AlarmStopEvent>> chunk(List<AlarmStopEvent> values, int batchSize) {
        int size = batchSize <= 0 ? 500 : batchSize;
        List<List<AlarmStopEvent>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += size) {
            chunks.add(values.subList(start, Math.min(start + size, values.size())));
        }
        return chunks;
    }

    private List<List<String>> chunkStrings(List<String> values, int batchSize) {
        int size = batchSize <= 0 ? 500 : batchSize;
        List<List<String>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += size) {
            chunks.add(values.subList(start, Math.min(start + size, values.size())));
        }
        return chunks;
    }

    private List<String> sampleCids(List<AlarmStopEvent> events) {
        return events.stream()
                .map(AlarmStopEvent::getAlarmCid)
                .filter(StringUtils::isNotBlank)
                .limit(5)
                .collect(Collectors.toList());
    }

    @Data
    private static class RouteLookupResult {
        /** ACTIVE route：可直接按 suffix 关闭业务报警。 */
        private Map<String, AlarmCidRoute> activeRouteMap = Collections.emptyMap();
        /** 全状态 route：用于识别 CLOSED 幂等成功，仍查不到才认定 ROUTE_MISSING。 */
        private Map<String, AlarmCidRoute> existingRouteMap = Collections.emptyMap();
    }

    private static class RouteLookupFailedException extends RuntimeException {
        private RouteLookupFailedException(Throwable cause) {
            super(cause);
        }
    }

    @Data
    private static class StopRouteContext {
        /** 原始 stop event，最终需要从 PENDING 流转到 APPLIED/FAILED。 */
        private AlarmStopEvent event;
        /** cid route，决定要操作哪个物理分片和 hot/stale route 表。 */
        private AlarmCidRoute route;
        /** 批量关闭业务分片和 route 表时使用的轻量 DTO。 */
        private AlarmStopApplyItem item;
        /** 业务报警补查结果，用于生成 side effect 事件。 */
        private Alarm alarm;
    }
}
