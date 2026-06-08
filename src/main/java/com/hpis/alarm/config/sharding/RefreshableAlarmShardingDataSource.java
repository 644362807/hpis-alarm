package com.hpis.alarm.config.sharding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stable Spring datasource bean that can atomically swap the inner ShardingSphere datasource.
 *
 * <p>MyBatis and Spring transaction infrastructure keep referencing this proxy. Refresh only
 * changes the delegate used by future {@code getConnection()} calls, while old delegates are
 * closed after a delay so in-flight transactions can finish on their original connection.</p>
 */
@Slf4j
public class RefreshableAlarmShardingDataSource extends AbstractDataSource {

    private final AtomicReference<DataSource> delegate;

    private final long closeOldDelayMs;

    private final ScheduledExecutorService closeExecutor = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "alarm-sharding-datasource-close");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    public RefreshableAlarmShardingDataSource(DataSource initialDataSource, long closeOldDelayMs) {
        if (initialDataSource == null) {
            throw new IllegalArgumentException("initialDataSource must not be null");
        }
        this.delegate = new AtomicReference<>(initialDataSource);
        this.closeOldDelayMs = Math.max(0L, closeOldDelayMs);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate.get().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return delegate.get().getConnection(username, password);
    }

    public void refresh(DataSource newDataSource) {
        if (newDataSource == null) {
            throw new IllegalArgumentException("newDataSource must not be null");
        }
        DataSource oldDataSource = delegate.getAndSet(newDataSource);
        if (oldDataSource == newDataSource) {
            return;
        }
        log.info("alarm sharding datasource refreshed, closeOldDelayMs={}", closeOldDelayMs);
        closeOldDataSource(oldDataSource, closeOldDelayMs);
    }

    DataSource currentDataSource() {
        return delegate.get();
    }

    private void closeOldDataSource(final DataSource oldDataSource, long delayMs) {
        if (oldDataSource == null) {
            return;
        }
        Runnable closeTask = new Runnable() {
            @Override
            public void run() {
                try {
                    if (oldDataSource instanceof AutoCloseable) {
                        ((AutoCloseable) oldDataSource).close();
                    }
                } catch (Exception ex) {
                    log.warn("Close old alarm sharding datasource failed", ex);
                }
            }
        };
        if (delayMs <= 0L) {
            closeTask.run();
            return;
        }
        closeExecutor.schedule(closeTask, delayMs, TimeUnit.MILLISECONDS);
    }
}
