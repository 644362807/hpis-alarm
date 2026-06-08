package com.hpis.alarm.config.sharding;

import org.junit.After;
import org.junit.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlarmShardingRuleRefreshServiceTest {

    @After
    public void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    public void shouldSwitchDataSourceWhenRefreshSucceeds() throws SQLException {
        Connection firstConnection = mock(Connection.class);
        Connection secondConnection = mock(Connection.class);
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(new FixedDataSource(firstConnection), 0L);
        AlarmShardingDataSourceFactory factory = mock(AlarmShardingDataSourceFactory.class);
        when(factory.createDataSource()).thenReturn(new FixedDataSource(secondConnection));
        AlarmShardingRuleRefreshService service = newService(refreshable, factory);

        boolean refreshed = service.refreshNow("manual-test");

        assertThat(refreshed).isTrue();
        assertThat(refreshable.getConnection()).isSameAs(secondConnection);
    }

    @Test
    public void shouldKeepOldDataSourceWhenRefreshFails() throws SQLException {
        Connection firstConnection = mock(Connection.class);
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(new FixedDataSource(firstConnection), 0L);
        AlarmShardingDataSourceFactory factory = mock(AlarmShardingDataSourceFactory.class);
        when(factory.createDataSource()).thenThrow(new IllegalStateException("boom"));
        AlarmShardingRuleRefreshService service = newService(refreshable, factory);

        boolean refreshed = service.refreshNow("manual-test");

        assertThat(refreshed).isFalse();
        assertThat(refreshable.getConnection()).isSameAs(firstConnection);
    }

    @Test
    public void shouldDebounceTableCreatedRefreshRequests() {
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(new FixedDataSource(mock(Connection.class)), 0L);
        AlarmShardingDataSourceFactory factory = mock(AlarmShardingDataSourceFactory.class);
        AlarmShardProperties properties = testProperties();
        properties.getRuleRefresh().setActiveOnTableCreatedDebounceMs(30_000L);
        AlarmShardingRuleRefreshService service =
                new AlarmShardingRuleRefreshService(refreshable, factory, properties);

        service.requestRefreshAfterCommit("table-created:202608_01");
        service.requestRefreshAfterCommit("table-created:202608_02");

        verify(factory, times(1)).createDataSource();
    }

    @Test
    public void shouldSkipNestedRefreshWhenPreviousRefreshIsRunning() {
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(new FixedDataSource(mock(Connection.class)), 0L);
        AlarmShardingDataSourceFactory factory = mock(AlarmShardingDataSourceFactory.class);
        final AlarmShardingRuleRefreshService[] serviceHolder = new AlarmShardingRuleRefreshService[1];
        final AtomicBoolean nestedResult = new AtomicBoolean(true);
        when(factory.createDataSource()).thenAnswer(invocation -> {
            nestedResult.set(serviceHolder[0].refreshNow("nested"));
            return new FixedDataSource(mock(Connection.class));
        });
        serviceHolder[0] = newService(refreshable, factory);

        boolean refreshed = serviceHolder[0].refreshNow("outer");

        assertThat(refreshed).isTrue();
        assertThat(nestedResult.get()).isFalse();
        verify(factory, times(1)).createDataSource();
    }


    @Test
    public void shouldRunTableCreatedRefreshAfterCommit() {
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(new FixedDataSource(mock(Connection.class)), 0L);
        AlarmShardingDataSourceFactory factory = mock(AlarmShardingDataSourceFactory.class);
        AlarmShardingRuleRefreshService service = newService(refreshable, factory);
        TransactionSynchronizationManager.initSynchronization();

        service.requestRefreshAfterCommit("table-created:202608_01");

        verify(factory, never()).createDataSource();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(factory, times(1)).createDataSource();
    }

    @Test
    public void shouldNotRunTableCreatedRefreshOnRollback() {
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(new FixedDataSource(mock(Connection.class)), 0L);
        AlarmShardingDataSourceFactory factory = mock(AlarmShardingDataSourceFactory.class);
        AlarmShardingRuleRefreshService service = newService(refreshable, factory);
        TransactionSynchronizationManager.initSynchronization();

        service.requestRefreshAfterCommit("table-created:202608_01");
        TransactionSynchronizationManager.clearSynchronization();

        verify(factory, never()).createDataSource();
    }

    private AlarmShardingRuleRefreshService newService(RefreshableAlarmShardingDataSource refreshable,
                                                       AlarmShardingDataSourceFactory factory) {
        return new AlarmShardingRuleRefreshService(refreshable, factory, testProperties());
    }

    private AlarmShardProperties testProperties() {
        AlarmShardProperties properties = new AlarmShardProperties();
        properties.getRuleRefresh().setActiveOnTableCreatedAsync(false);
        properties.getRuleRefresh().setActiveOnTableCreatedDebounceMs(0L);
        return properties;
    }

    private static final class FixedDataSource extends AbstractDataSource {

        private final Connection connection;

        private FixedDataSource(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }
    }
}
