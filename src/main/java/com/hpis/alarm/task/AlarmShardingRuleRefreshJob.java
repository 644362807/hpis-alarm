package com.hpis.alarm.task;

import com.hpis.alarm.config.sharding.AlarmShardingRuleRefreshService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically rebuilds ShardingSphere actualDataNodes without restarting the service.
 *
 * <p>The scheduled job delegates to the shared refresh service so active table-created refresh
 * and monthly refresh use the same single-flight and failure handling.</p>
 */
@Component
@ConditionalOnBean(AlarmShardingRuleRefreshService.class)
@ConditionalOnProperty(prefix = "alarm.sharding.rule-refresh", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AlarmShardingRuleRefreshJob {

    private final AlarmShardingRuleRefreshService refreshService;

    public AlarmShardingRuleRefreshJob(AlarmShardingRuleRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @Scheduled(cron = "${alarm.sharding.rule-refresh.cron:0 5 0 1 * ?}")
    public void refreshRule() {
        refreshService.refreshNow("monthly-scheduled");
    }
}
