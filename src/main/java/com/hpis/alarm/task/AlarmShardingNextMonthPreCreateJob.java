package com.hpis.alarm.task;

import com.hpis.alarm.config.sharding.AlarmMonthlySliceTableManager;
import com.hpis.alarm.config.sharding.AlarmShardProperties;
import com.hpis.alarm.config.sharding.AlarmShardingRuleRefreshService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Pre-creates the next month's first physical slice near month end, then refreshes actualDataNodes.
 */
@Slf4j
@Component
@ConditionalOnBean({AlarmMonthlySliceTableManager.class, AlarmShardingRuleRefreshService.class})
@ConditionalOnProperty(prefix = "alarm.sharding.rule-refresh", name = {"enabled", "month-end-pre-create-enabled"},
        havingValue = "true", matchIfMissing = true)
public class AlarmShardingNextMonthPreCreateJob {

    private static final String REFRESH_REASON = "month-end-precreate";

    private final AlarmMonthlySliceTableManager tableManager;

    private final AlarmShardingRuleRefreshService refreshService;

    private final AlarmShardProperties properties;

    public AlarmShardingNextMonthPreCreateJob(AlarmMonthlySliceTableManager tableManager,
                                              AlarmShardingRuleRefreshService refreshService,
                                              AlarmShardProperties properties) {
        this.tableManager = tableManager;
        this.refreshService = refreshService;
        this.properties = properties;
    }

    @Scheduled(cron = "${alarm.sharding.rule-refresh.month-end-pre-create-cron:0 50 23 * * ?}")
    public void preCreateNextMonth() {
        preCreateNextMonth(LocalDate.now());
    }

    void preCreateNextMonth(LocalDate today) {
        if (properties.getRuleRefresh().isMonthEndPreCreateCheckLastDay() && !isLastDayOfMonth(today)) {
            log.debug("alarm sharding next month pre-create skipped because today is not month end, today={}", today);
            return;
        }
        int maxSliceNo = properties.getRuleRefresh().safeMonthEndPreCreateNextMonthMaxSliceNo();
        try {
            boolean created = tableManager.preCreateNextMonthSlices(today, maxSliceNo);
            boolean refreshed = refreshService.refreshNow(REFRESH_REASON);
            log.info("alarm sharding next month pre-create finished, today={}, maxSliceNo={}, created={}, refreshed={}",
                    today, maxSliceNo, created, refreshed);
        } catch (Exception ex) {
            log.error("alarm sharding next month pre-create failed, keep old datasource, today={}, maxSliceNo={}",
                    today, maxSliceNo, ex);
        }
    }

    boolean isLastDayOfMonth(LocalDate today) {
        return today != null && today.getDayOfMonth() == today.lengthOfMonth();
    }
}
