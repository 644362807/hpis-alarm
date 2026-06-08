package com.hpis.alarm.config.sharding;

import org.junit.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class RefreshableAlarmShardingDataSourceTest {

    @Test
    public void shouldSwitchDelegateAfterRefresh() throws SQLException {
        Connection firstConnection = mock(Connection.class);
        Connection secondConnection = mock(Connection.class);
        CloseableDataSource firstDataSource = new CloseableDataSource(firstConnection);
        CloseableDataSource secondDataSource = new CloseableDataSource(secondConnection);
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(firstDataSource, 0L);

        assertThat(refreshable.getConnection()).isSameAs(firstConnection);

        refreshable.refresh(secondDataSource);

        assertThat(refreshable.getConnection()).isSameAs(secondConnection);
        assertThat(firstDataSource.closed).isTrue();
        assertThat(secondDataSource.closed).isFalse();
    }

    @Test
    public void shouldKeepCurrentDelegateWhenNewDataSourceIsSameInstance() throws SQLException {
        Connection connection = mock(Connection.class);
        CloseableDataSource dataSource = new CloseableDataSource(connection);
        RefreshableAlarmShardingDataSource refreshable =
                new RefreshableAlarmShardingDataSource(dataSource, 0L);

        refreshable.refresh(dataSource);

        assertThat(refreshable.getConnection()).isSameAs(connection);
        assertThat(dataSource.closed).isFalse();
    }

    static final class CloseableDataSource extends AbstractDataSource implements AutoCloseable {

        private final Connection connection;

        private boolean closed;

        CloseableDataSource(Connection connection) {
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

        @Override
        public void close() {
            closed = true;
        }
    }
}
