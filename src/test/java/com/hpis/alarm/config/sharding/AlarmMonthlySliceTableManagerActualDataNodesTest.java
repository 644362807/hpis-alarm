package com.hpis.alarm.config.sharding;

import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AlarmMonthlySliceTableManagerActualDataNodesTest {

    @Test
    public void shouldReturnAllTablesWhenTimeRangeIsMissing() {
        Set<String> tables = monthlyTables();
        AlarmMonthlySliceTableManager manager = managerWithTables(tables);

        assertThat(manager.listTablesByTimeRange("alarm", null, null)).isEqualTo(tables);
    }

    @Test
    public void shouldKeepStartMonthAndLaterMonthsForLowerBoundOnly() throws Exception {
        AlarmMonthlySliceTableManager manager = managerWithTables(monthlyTables());

        Set<String> routed = manager.listTablesByTimeRange("alarm", time("2026-07-15 00:00:00"), null);

        assertThat(routed).containsExactly(
                "alarm_202607_00", "alarm_202607_01", "alarm_202608_00");
    }

    @Test
    public void shouldKeepEndMonthAndEarlierMonthsForUpperBoundOnly() throws Exception {
        AlarmMonthlySliceTableManager manager = managerWithTables(monthlyTables());

        Set<String> routed = manager.listTablesByTimeRange("alarm", null, time("2026-07-15 00:00:00"));

        assertThat(routed).containsExactly(
                "alarm_202606_00", "alarm_202607_00", "alarm_202607_01");
    }

    @Test
    public void shouldKeepAllSlicesInMonthsCoveredByBothBounds() throws Exception {
        AlarmMonthlySliceTableManager manager = managerWithTables(monthlyTables());

        Set<String> routed = manager.listTablesByTimeRange(
                "alarm", time("2026-07-01 00:00:00"), time("2026-07-31 23:59:59"));

        assertThat(routed).containsExactly("alarm_202607_00", "alarm_202607_01");
    }

    @Test
    public void shouldRegisterHistoryActualTablesCurrentMonthAllSlicesAndNextMonthWarmSlices() {
        AlarmShardProperties properties = new AlarmShardProperties();
        AlarmMonthlySliceTableManager manager = new AlarmMonthlySliceTableManager(null, properties);
        Set<String> existingTables = new LinkedHashSet<>(Arrays.asList(
                "alarm_202604_00",
                "alarm_202605_01",
                "alarm_0",
                "alarm_handle_202604_00",
                "alarm_202606_xx"));

        Set<String> actualTables = manager.buildActualDataNodeTables("alarm", existingTables,
                LocalDate.of(2026, 6, 15));

        assertThat(actualTables)
                .contains("alarm_202604_00", "alarm_202605_01")
                .contains("alarm_202606_00", "alarm_202606_255")
                .contains("alarm_202607_00", "alarm_202607_09")
                .doesNotContain("alarm_0", "alarm_handle_202604_00", "alarm_202606_xx", "alarm_202607_10");
    }

    @Test
    public void shouldDefaultStartupPreCreateCurrentMonthOnly() {
        AlarmShardProperties properties = new AlarmShardProperties();

        assertThat(properties.getPreCreateMonths()).isEqualTo(0);
    }

    @Test
    public void shouldUseConfiguredNextMonthMaxSliceNo() {
        AlarmShardProperties properties = new AlarmShardProperties();
        properties.getActualDataNodes().setCurrentMonthMaxSliceNo(0);
        properties.getActualDataNodes().setNextMonthMaxSliceNo(3);
        AlarmMonthlySliceTableManager manager = new AlarmMonthlySliceTableManager(null, properties);

        Set<String> actualTables = manager.buildActualDataNodeTables("alarm_handle", new LinkedHashSet<>(),
                LocalDate.of(2026, 6, 1));

        assertThat(actualTables)
                .contains("alarm_handle_202606_00")
                .contains("alarm_handle_202607_00", "alarm_handle_202607_01",
                        "alarm_handle_202607_02", "alarm_handle_202607_03")
                .doesNotContain("alarm_handle_202607_04");
    }

    @Test
    public void shouldClampPreRegisteredSliceNumbersToEncodableRange() {
        AlarmShardProperties properties = new AlarmShardProperties();
        properties.getActualDataNodes().setCurrentMonthMaxSliceNo(300);
        properties.getActualDataNodes().setNextMonthMaxSliceNo(300);
        AlarmMonthlySliceTableManager manager = new AlarmMonthlySliceTableManager(null, properties);

        Set<String> actualTables = manager.buildActualDataNodeTables("alarm_electrolytic_cell",
                new LinkedHashSet<>(), LocalDate.of(2026, 6, 1));

        assertThat(actualTables)
                .contains("alarm_electrolytic_cell_202606_255", "alarm_electrolytic_cell_202607_255")
                .doesNotContain("alarm_electrolytic_cell_202606_256", "alarm_electrolytic_cell_202607_256");
    }

    @Test
    public void shouldRequestOneRefreshAfterCreatingTablesForSameSuffix() throws Exception {
        AlarmShardProperties properties = new AlarmShardProperties();
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        ObjectProvider<AlarmShardingRuleRefreshService> refreshProvider = mock(ObjectProvider.class);
        when(refreshProvider.getIfAvailable()).thenReturn(refreshService);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.createStatement()).thenReturn(statement);
        ResultSet physicalAlarmMissing = resultSet(false);
        ResultSet alarmTemplateExists = resultSet(true);
        ResultSet physicalHandleMissing = resultSet(false);
        ResultSet handleTemplateExists = resultSet(true);
        ResultSet physicalEcMissing = resultSet(false);
        ResultSet ecTemplateExists = resultSet(true);
        ResultSet handleBeginTimeMissing = resultSet(false);
        ResultSet ecBeginTimeMissing = resultSet(false);
        ResultSet ecDelFlagMissing = resultSet(false);
        when(metaData.getTables(isNull(), isNull(), anyString(), any(String[].class))).thenReturn(
                physicalAlarmMissing, alarmTemplateExists,
                physicalHandleMissing, handleTemplateExists,
                physicalEcMissing, ecTemplateExists);
        when(metaData.getColumns(isNull(), isNull(), anyString(), anyString())).thenReturn(
                handleBeginTimeMissing, ecBeginTimeMissing, ecDelFlagMissing);
        AlarmMonthlySliceTableManager manager =
                new AlarmMonthlySliceTableManager(dataSource, properties, refreshProvider);
        ReflectionTestUtils.setField(manager, "initialized", true);

        boolean created = manager.createTablesForSuffix("202608_01");

        assertThat(created).isTrue();
        verify(statement, times(3)).execute(org.mockito.ArgumentMatchers.startsWith("CREATE TABLE IF NOT EXISTS"));
        verify(statement, times(1)).execute(argThat(sql -> sql.startsWith(
                "ALTER TABLE alarm_electrolytic_cell_202608_01 ADD COLUMN del_flag")));
        verify(refreshService, times(1)).requestRefreshAfterCommit("table-created:202608_01");
    }

    @Test
    public void shouldNotRequestRefreshWhenConfirmedTablesAlreadyExist() throws Exception {
        AlarmShardProperties properties = new AlarmShardProperties();
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        ObjectProvider<AlarmShardingRuleRefreshService> refreshProvider = mock(ObjectProvider.class);
        when(refreshProvider.getIfAvailable()).thenReturn(refreshService);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.createStatement()).thenReturn(statement);
        ResultSet physicalAlarmMissing = resultSet(false);
        ResultSet alarmTemplateExists = resultSet(true);
        ResultSet physicalHandleMissing = resultSet(false);
        ResultSet handleTemplateExists = resultSet(true);
        ResultSet physicalEcMissing = resultSet(false);
        ResultSet ecTemplateExists = resultSet(true);
        ResultSet handleBeginTimeMissing = resultSet(false);
        ResultSet ecBeginTimeMissing = resultSet(false);
        ResultSet ecDelFlagMissing = resultSet(false);
        when(metaData.getTables(isNull(), isNull(), anyString(), any(String[].class))).thenReturn(
                physicalAlarmMissing, alarmTemplateExists,
                physicalHandleMissing, handleTemplateExists,
                physicalEcMissing, ecTemplateExists);
        when(metaData.getColumns(isNull(), isNull(), anyString(), anyString())).thenReturn(
                handleBeginTimeMissing, ecBeginTimeMissing, ecDelFlagMissing);
        AlarmMonthlySliceTableManager manager =
                new AlarmMonthlySliceTableManager(dataSource, properties, refreshProvider);
        ReflectionTestUtils.setField(manager, "initialized", true);
        manager.createTablesForSuffix("202608_01");

        boolean created = manager.createTablesForSuffix("202608_01");

        assertThat(created).isFalse();
        verify(refreshService, times(1)).requestRefreshAfterCommit("table-created:202608_01");
    }

    @Test
    public void shouldPreCreateNextMonthSlicesWithoutDebouncedRefreshRequest() throws Exception {
        AlarmShardProperties properties = new AlarmShardProperties();
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        AlarmShardingRuleRefreshService refreshService = mock(AlarmShardingRuleRefreshService.class);
        ObjectProvider<AlarmShardingRuleRefreshService> refreshProvider = mock(ObjectProvider.class);
        when(refreshProvider.getIfAvailable()).thenReturn(refreshService);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        ResultSet physicalAlarmMissing = resultSet(false);
        ResultSet alarmTemplateExists = resultSet(true);
        ResultSet physicalHandleMissing = resultSet(false);
        ResultSet handleTemplateExists = resultSet(true);
        ResultSet physicalEcMissing = resultSet(false);
        ResultSet ecTemplateExists = resultSet(true);
        ResultSet handleBeginTimeMissing = resultSet(false);
        ResultSet ecBeginTimeMissing = resultSet(false);
        ResultSet ecDelFlagMissing = resultSet(false);
        when(metaData.getTables(isNull(), isNull(), anyString(), any(String[].class))).thenReturn(
                physicalAlarmMissing, alarmTemplateExists,
                physicalHandleMissing, handleTemplateExists,
                physicalEcMissing, ecTemplateExists);
        when(metaData.getColumns(isNull(), isNull(), anyString(), anyString())).thenReturn(
                handleBeginTimeMissing, ecBeginTimeMissing, ecDelFlagMissing);
        AlarmMonthlySliceTableManager manager =
                new AlarmMonthlySliceTableManager(dataSource, properties, refreshProvider);
        ReflectionTestUtils.setField(manager, "initialized", true);

        boolean created = manager.preCreateNextMonthSlices(LocalDate.of(2026, 6, 30), 0);

        assertThat(created).isTrue();
        verify(statement, times(3)).execute(org.mockito.ArgumentMatchers.startsWith("CREATE TABLE IF NOT EXISTS"));
        verify(preparedStatement).setString(1, "202607");
        verify(preparedStatement).setInt(2, 0);
        verify(preparedStatement).setString(3, "202607_00");
        verify(refreshService, times(0)).requestRefreshAfterCommit(anyString());
    }

    private ResultSet resultSet(boolean exists) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(exists);
        return resultSet;
    }

    private AlarmMonthlySliceTableManager managerWithTables(Set<String> tables) {
        AlarmMonthlySliceTableManager manager = spy(
                new AlarmMonthlySliceTableManager(null, new AlarmShardProperties()));
        doReturn(tables).when(manager).listAllShardTables("alarm");
        return manager;
    }

    private Set<String> monthlyTables() {
        return new LinkedHashSet<>(Arrays.asList(
                "alarm_202606_00",
                "alarm_202607_00",
                "alarm_202607_01",
                "alarm_202608_00"));
    }

    private Date time(String value) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(value);
    }
}
