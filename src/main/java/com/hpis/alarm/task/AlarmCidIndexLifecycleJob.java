package com.hpis.alarm.task;

import com.hpis.alarm.config.sharding.AlarmCidIndexService;
import com.hpis.alarm.config.sharding.AlarmShardContext;
import com.hpis.alarm.config.sharding.AlarmShardProperties;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.enums.AlarmStatusEnums;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.common.core.utils.DateUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * cid 路由生命周期低流量任务。
 *
 * <p>这些任务只维护 alarm_cid_index/alarm_cid_stale_index，不移动业务数据。hot 转 stale
 * 使用“先写 stale、再删 hot”的幂等流程；stale 过期时必须先关闭真实业务报警，再把路由
 * 标记为 CLOSED，避免出现业务仍未结束但 cid 路由已经消失的危险状态。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmCidIndexLifecycleJob {

    private final AlarmCidIndexService alarmCidIndexService;

    private final AlarmMapper alarmMapper;

    private final AlarmShardProperties alarmShardProperties;

    public AlarmCidIndexLifecycleJob(AlarmCidIndexService alarmCidIndexService,
                                     AlarmMapper alarmMapper,
                                     AlarmShardProperties alarmShardProperties) {
        this.alarmCidIndexService = alarmCidIndexService;
        this.alarmMapper = alarmMapper;
        this.alarmShardProperties = alarmShardProperties;
    }

    @Scheduled(cron = "${alarm.sharding.cid-index.cleanup-cron:0 0 2 * * ?}")
    public void cleanupClosedRoutes() {
        int deleted = alarmCidIndexService.cleanupClosedRoutes();
        if (alarmShardProperties.getCidIndex().isLogEnabled() && deleted > 0) {
            log.info("已清理关闭状态 cid 路由 {} 条", deleted);
        }
    }

    @Scheduled(cron = "${alarm.sharding.cid-index.transfer-cron:0 10 2 * * ?}")
    public void transferHotToStale() {
        int transferred = alarmCidIndexService.transferHotToStale();
        if (alarmShardProperties.getCidIndex().isLogEnabled() && transferred > 0) {
            log.info("已将热点 cid 路由转入 stale {} 条", transferred);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "${alarm.sharding.cid-index.stale-timeout-cron:0 20 2 * * ?}")
    public void closeExpiredStaleRoutes() {
        List<AlarmCidRoute> routes = alarmCidIndexService.findExpiredActiveStaleRoutes();
        Date now = DateUtils.getNowDate();
        for (AlarmCidRoute route : routes) {
            try {
                AlarmShardContext.setTableSuffix(route.getTableSuffix());
                Alarm alarm = new Alarm();
                alarm.setAlarmId(route.getAlarmId());
                alarm.setAlarmEndtime(now);
                alarm.setAlarmStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
                alarm.setUpdateTime(now);
                alarmMapper.updateAlarm(alarm);
                alarmCidIndexService.closeRoute(route, now);
            } finally {
                AlarmShardContext.clear();
            }
        }
        if (alarmShardProperties.getCidIndex().isLogEnabled() && !routes.isEmpty()) {
            log.info("已超时关闭 stale cid 路由 {} 条", routes.size());
        }
    }
}
