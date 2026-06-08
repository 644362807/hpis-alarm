package com.hpis.alarm.config.sharding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared refresh entry for scheduled and table-created ShardingSphere rule refresh.
 */
@Slf4j
public class AlarmShardingRuleRefreshService {

    private final RefreshableAlarmShardingDataSource refreshableDataSource;

    private final AlarmShardingDataSourceFactory dataSourceFactory;

    private final AlarmShardProperties properties;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private final AtomicLong lastTableCreatedRefreshRequestTime = new AtomicLong(0L);

    private final Executor asyncExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "alarm-sharding-rule-refresh");
            thread.setDaemon(true);
            return thread;
        }
    });

    public AlarmShardingRuleRefreshService(RefreshableAlarmShardingDataSource refreshableDataSource,
                                           AlarmShardingDataSourceFactory dataSourceFactory,
                                           AlarmShardProperties properties) {
        this.refreshableDataSource = refreshableDataSource;
        this.dataSourceFactory = dataSourceFactory;
        this.properties = properties;
    }

    public boolean refreshNow(String reason) {
        if (!refreshing.compareAndSet(false, true)) {
            log.warn("alarm sharding rule refresh skipped because previous refresh is still running, reason={}", reason);
            return false;
        }
        try {
            DataSource newDataSource = dataSourceFactory.createDataSource();
            refreshableDataSource.refresh(newDataSource);
            log.info("alarm sharding rule refresh success, reason={}", reason);
            return true;
        } catch (Exception ex) {
            log.error("alarm sharding rule refresh failed, keep old datasource, reason={}", reason, ex);
            return false;
        } finally {
            refreshing.set(false);
        }
    }

    public void requestRefreshAfterCommit(final String reason) {
        if (!properties.getRuleRefresh().isActiveOnTableCreatedEnabled()) {
            log.debug("alarm sharding table-created refresh disabled, reason={}", reason);
            return;
        }
        Runnable refreshRequest = new Runnable() {
            @Override
            public void run() {
                requestDebouncedTableCreatedRefresh(reason);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    refreshRequest.run();
                }
            });
            return;
        }
        refreshRequest.run();
    }

    private void requestDebouncedTableCreatedRefresh(final String reason) {
        long now = System.currentTimeMillis();
        long debounceMs = properties.getRuleRefresh().safeActiveOnTableCreatedDebounceMs();
        long lastRequestTime = lastTableCreatedRefreshRequestTime.get();
        if (debounceMs > 0L && now - lastRequestTime < debounceMs) {
            log.info("alarm sharding table-created refresh request debounced, reason={}, debounceMs={}",
                    reason, debounceMs);
            return;
        }
        if (!lastTableCreatedRefreshRequestTime.compareAndSet(lastRequestTime, now)) {
            log.info("alarm sharding table-created refresh request merged, reason={}", reason);
            return;
        }
        Runnable refreshTask = new Runnable() {
            @Override
            public void run() {
                refreshNow(reason);
            }
        };
        if (properties.getRuleRefresh().isActiveOnTableCreatedAsync()) {
            asyncExecutor.execute(refreshTask);
        } else {
            refreshTask.run();
        }
    }
}
