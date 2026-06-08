package com.hpis.alarm.config.sharding;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.ComplexShardingStrategyConfiguration;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds ShardingSphere 4.1.1 datasource instances from the current table registry.
 *
 * <p>ShardingSphere 4.1.1 does not expand {@code ds.alarm_*} from database metadata at runtime.
 * Every refresh must rebuild a datasource with explicit actualDataNodes; callers then swap it
 * through {@link RefreshableAlarmShardingDataSource}.</p>
 */
@Slf4j
public class AlarmShardingDataSourceFactory {

    private static final String DATA_SOURCE_NAME = "ds";

    private final DataSourceProperties alarmPhysicalDataSourceProperties;

    private final AlarmMonthlySliceTableManager tableManager;

    private final AlarmTimeCapacityShardingAlgorithm algorithm;

    public AlarmShardingDataSourceFactory(DataSourceProperties alarmPhysicalDataSourceProperties,
                                          AlarmMonthlySliceTableManager tableManager,
                                          AlarmTimeCapacityShardingAlgorithm algorithm) {
        this.alarmPhysicalDataSourceProperties = alarmPhysicalDataSourceProperties;
        this.tableManager = tableManager;
        this.algorithm = algorithm;
    }

    public DataSource createDataSource() {
        HikariDataSource physicalDataSource = createPhysicalDataSource();
        Map<String, DataSource> dataSourceMap = new HashMap<>();
        dataSourceMap.put(DATA_SOURCE_NAME, physicalDataSource);

        ShardingRuleConfiguration shardingRuleConfig = new ShardingRuleConfiguration();
        shardingRuleConfig.setDefaultDataSourceName(DATA_SOURCE_NAME);
        shardingRuleConfig.getTableRuleConfigs().add(buildTableRule("alarm",
                "alarm_id,alarm_cid,device_sn,irms_sn,alarm_beginTime"));
        shardingRuleConfig.getTableRuleConfigs().add(buildTableRule("alarm_handle",
                "alarm_id,alarm_beginTime"));
        shardingRuleConfig.getTableRuleConfigs().add(buildTableRule("alarm_electrolytic_cell",
                "alarm_id,alarm_beginTime"));
        shardingRuleConfig.getBindingTableGroups().add("alarm,alarm_handle,alarm_electrolytic_cell");

        try {
            return ShardingDataSourceFactory.createDataSource(dataSourceMap, shardingRuleConfig, new Properties());
        } catch (SQLException ex) {
            physicalDataSource.close();
            throw new IllegalStateException("Create hpis-alarm sharding datasource failed", ex);
        }
    }

    private HikariDataSource createPhysicalDataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(alarmPhysicalDataSourceProperties.getUrl());
        dataSource.setUsername(alarmPhysicalDataSourceProperties.getUsername());
        dataSource.setPassword(alarmPhysicalDataSourceProperties.getPassword());
        if (alarmPhysicalDataSourceProperties.getDriverClassName() != null
                && !"".equals(alarmPhysicalDataSourceProperties.getDriverClassName().trim())) {
            dataSource.setDriverClassName(alarmPhysicalDataSourceProperties.getDriverClassName());
        }
        return dataSource;
    }

    private TableRuleConfiguration buildTableRule(String logicTableName, String shardingColumns) {
        Set<String> actualTables = tableManager.listActualDataNodeTables(logicTableName);
        String actualDataNodes = actualTables.stream()
                .map(tableName -> DATA_SOURCE_NAME + "." + tableName)
                .collect(Collectors.joining(","));
        if (actualDataNodes.isEmpty()) {
            throw new IllegalStateException("No actual data nodes found for logic table " + logicTableName);
        }
        log.info("alarm sharding actualDataNodes refreshed, logicTableName={}, actualTableCount={}",
                logicTableName, actualTables.size());

        TableRuleConfiguration tableRule = new TableRuleConfiguration(logicTableName, actualDataNodes);
        tableRule.setTableShardingStrategyConfig(
                new ComplexShardingStrategyConfiguration(shardingColumns, algorithm));
        return tableRule;
    }
}
