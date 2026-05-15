package com.hpis.alarm.config.sharding;

import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.dto.AlarmStopApplyItem;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.mapper.AlarmCidIndexMapper;
import com.hpis.common.core.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 外部 cid 生命周期路由服务。
 *
 * <p>该服务替代旧的全量 alarm_shard_route 写入。cid 主要用于“报警结束时找到原报警”，
 * 关闭后的长期历史查询概率很低，因此这里只维护 hot/stale 两类在线路由。正常关闭后把
 * 路由标记为 CLOSED，低流量任务再物理删除，避免索引表随总报警量无限增长。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmCidIndexService {

    private final AlarmMonthlySliceTableManager tableManager;

    private final AlarmCidIndexMapper cidIndexMapper;

    private final AlarmIdCodec alarmIdCodec;

    private final AlarmShardProperties properties;

    public AlarmCidIndexService(AlarmMonthlySliceTableManager tableManager,
                                AlarmCidIndexMapper cidIndexMapper,
                                AlarmIdCodec alarmIdCodec,
                                AlarmShardProperties properties) {
        this.tableManager = tableManager;
        this.cidIndexMapper = cidIndexMapper;
        this.alarmIdCodec = alarmIdCodec;
        this.properties = properties;
    }

    public AlarmMonthlySliceTableManager.ShardAllocation allocate(Date alarmBeginTime) {
        return tableManager.allocate(alarmBeginTime);
    }

    public long nextAlarmId(Date alarmBeginTime, int sliceNo, long rowNo, long snowflakeSeed) {
        return alarmIdCodec.nextId(alarmBeginTime, sliceNo, rowNo, snowflakeSeed);
    }

    public AlarmCidRoute saveActiveHotRoute(Alarm alarm, String tableSuffix) {
        if (alarm == null || alarm.getAlarmCid() == null || "".equals(alarm.getAlarmCid().trim())) {
            return null;
        }
        AlarmCidRoute route = buildRoute(alarm, tableSuffix);
        cidIndexMapper.upsertHotActive(route);
        return route;
    }

    public AlarmCidRoute findActiveRouteByCid(String alarmCid) {
        AlarmCidRoute hot = cidIndexMapper.selectHotByCid(alarmCid);
        if (isActive(hot)) {
            return hot;
        }
        AlarmCidRoute stale = cidIndexMapper.selectStaleByCid(alarmCid);
        if (isActive(stale)) {
            return stale;
        }
        return null;
    }

    public AlarmCidRoute findRouteByCid(String alarmCid) {
        AlarmCidRoute hot = cidIndexMapper.selectHotByCid(alarmCid);
        if (hot != null) {
            return hot;
        }
        return cidIndexMapper.selectStaleByCid(alarmCid);
    }

    public List<AlarmCidRoute> findActiveRoutesByDeviceSn(String deviceSn) {
        List<AlarmCidRoute> routes = new ArrayList<>();
        String excludedType = AlarmTypeEnums.ALARM_TYPE_ENUMS_3.getKey();
        routes.addAll(cidIndexMapper.selectActiveHotByDeviceSn(deviceSn, excludedType));
        routes.addAll(cidIndexMapper.selectActiveStaleByDeviceSn(deviceSn, excludedType));
        return routes;
    }

    public List<AlarmCidRoute> findActiveRoutesByIrmsSn(String irmsSn) {
        List<AlarmCidRoute> routes = new ArrayList<>();
        routes.addAll(cidIndexMapper.selectActiveHotByIrmsSn(irmsSn));
        routes.addAll(cidIndexMapper.selectActiveStaleByIrmsSn(irmsSn));
        return routes;
    }

    public void closeRoute(AlarmCidRoute route, Date alarmEndTime) {
        if (route == null || route.getAlarmId() == null) {
            return;
        }
        Date deleteAfter = DateUtils.getNowDate();
        if (AlarmCidRoute.SOURCE_STALE.equals(route.getIndexSource())) {
            cidIndexMapper.closeStaleByAlarmId(route.getAlarmId(), alarmEndTime, deleteAfter);
        } else {
            cidIndexMapper.closeHotByAlarmId(route.getAlarmId(), alarmEndTime, deleteAfter);
        }
    }

    public void closeRoutes(List<AlarmCidRoute> routes, Date alarmEndTime) {
        if (routes == null || routes.isEmpty()) {
            return;
        }
        for (AlarmCidRoute route : routes) {
            closeRoute(route, alarmEndTime);
        }
    }

    /**
     * 批量关闭 hot/stale cid 路由。
     *
     * <p>stop worker 已经按业务表 suffix 分组，这里只按 route 来源再拆成 hot/stale 两批，
     * 避免 2 万级消警时每条报警各执行一次 closeRoute。deleteAfter 对同一批统一计算即可，
     * 因为它只控制路由表后续低流量物理清理时间，不参与业务结束时间判断。</p>
     */
    public void closeRoutesByItems(List<AlarmStopApplyItem> hotItems,
                                   List<AlarmStopApplyItem> staleItems,
                                   Date deleteAfter) {
        Date actualDeleteAfter = deleteAfter == null ? DateUtils.getNowDate() : deleteAfter;
        if (hotItems != null && !hotItems.isEmpty()) {
            cidIndexMapper.closeHotBatch(hotItems, actualDeleteAfter);
        }
        if (staleItems != null && !staleItems.isEmpty()) {
            cidIndexMapper.closeStaleBatch(staleItems, actualDeleteAfter);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public int transferHotToStale() {
        Date cutoffTime = addHours(DateUtils.getNowDate(), -properties.getCidIndex().getHotHours());
        List<AlarmCidRoute> routes = cidIndexMapper.selectHotTransferCandidates(
                cutoffTime, properties.getCidIndex().getTransferBatchSize());
        int transferred = 0;
        for (AlarmCidRoute route : routes) {
            route.setStaleTime(DateUtils.getNowDate());
            route.setExpireTime(addDays(route.getStaleTime(), properties.getCidIndex().getStaleExpireDays()));
            route.setDeleteAfter(null);
            cidIndexMapper.upsertStaleActive(route);
            transferred += cidIndexMapper.deleteHotActiveByAlarmId(route.getAlarmId());
        }
        return transferred;
    }

    public List<AlarmCidRoute> findExpiredActiveStaleRoutes() {
        return cidIndexMapper.selectExpiredActiveStale(
                DateUtils.getNowDate(), properties.getCidIndex().getCleanupBatchSize());
    }

    public int cleanupClosedRoutes() {
        Date now = DateUtils.getNowDate();
        int limit = properties.getCidIndex().getCleanupBatchSize();
        return cidIndexMapper.deleteClosedHot(now, limit) + cidIndexMapper.deleteClosedStale(now, limit);
    }

    private AlarmCidRoute buildRoute(Alarm alarm, String tableSuffix) {
        AlarmCidRoute route = new AlarmCidRoute();
        route.setAlarmCid(alarm.getAlarmCid());
        route.setAlarmId(alarm.getAlarmId());
        route.setAlarmBegintime(alarm.getAlarmBegintime());
        route.setAlarmEndtime(alarm.getAlarmEndtime());
        route.setTableSuffix(tableSuffix);
        route.setDeviceSn(alarm.getDeviceSn());
        route.setIrmsSn(alarm.getIrmsSn());
        route.setAlarmType(alarm.getAlarmType());
        route.setRouteStatus(AlarmCidRoute.STATUS_ACTIVE);
        return route;
    }

    private boolean isActive(AlarmCidRoute route) {
        return route != null && AlarmCidRoute.STATUS_ACTIVE.equals(route.getRouteStatus());
    }

    private Date addHours(Date source, int hours) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(source);
        calendar.add(Calendar.HOUR_OF_DAY, hours);
        return calendar.getTime();
    }

    private Date addDays(Date source, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(source);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTime();
    }
}
