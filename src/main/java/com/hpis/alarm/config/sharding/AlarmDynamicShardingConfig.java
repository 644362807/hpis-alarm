package com.hpis.alarm.config.sharding;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.ComplexShardingStrategyConfiguration;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * hpis-alarm Java API 分片数据源配置。
 *
 * <p>ShardingSphere 4.1.1 的 YAML inline 规则适合固定取模，但无法基于元数据表动态选择
 * 月内容量子表。因此这里复用 hpis-quad 在 4.1.x 中已经验证过的 Java API 创建数据源方式，
 * 并把表策略换成 ComplexShardingStrategyConfiguration。</p>
 *
 * <p>启用前必须关闭 Nacos 中旧的 alarm/alarm_handle/alarm_electrolytic_cell inline
 * 分片规则，否则 Spring 容器中会同时出现两套分片数据源，路由结果不可控。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(AlarmShardProperties.class)
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmDynamicShardingConfig {

    @Bean("alarmPhysicalDataSourceProperties")
    @ConfigurationProperties(prefix = "spring.shardingsphere.datasource.ds")
    public DataSourceProperties alarmPhysicalDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("alarmPhysicalDataSource")
    public DataSource alarmPhysicalDataSource(
            @Qualifier("alarmPhysicalDataSourceProperties") DataSourceProperties alarmPhysicalDataSourceProperties) {
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

    @Bean
    public AlarmMonthlySliceTableManager alarmMonthlySliceTableManager(
            @Qualifier("alarmPhysicalDataSource") DataSource alarmPhysicalDataSource,
                                                                       AlarmShardProperties properties) {
        AlarmMonthlySliceTableManager tableManager = new AlarmMonthlySliceTableManager(alarmPhysicalDataSource, properties);
        tableManager.init();
        return tableManager;
    }

    @Bean
    public AlarmCidIndexRepository alarmCidIndexRepository(
            @Qualifier("alarmPhysicalDataSource") DataSource alarmPhysicalDataSource) {
        return new AlarmCidIndexRepository(alarmPhysicalDataSource);
    }

    @Bean
    public AlarmIdCodec alarmIdCodec(AlarmShardProperties properties) {
        return new AlarmIdCodec(properties);
    }

    @Bean
    public AlarmTimeCapacityShardingAlgorithm alarmTimeCapacityShardingAlgorithm(
            AlarmMonthlySliceTableManager tableManager,
            AlarmCidIndexRepository cidIndexRepository,
            AlarmIdCodec alarmIdCodec) {
        return new AlarmTimeCapacityShardingAlgorithm(tableManager, cidIndexRepository, alarmIdCodec);
    }

    @Bean
    @Primary
    public DataSource shardingDataSource(@Qualifier("alarmPhysicalDataSource") DataSource alarmPhysicalDataSource,
                                         AlarmTimeCapacityShardingAlgorithm algorithm) {
        Map<String, DataSource> dataSourceMap = new HashMap<>();
        dataSourceMap.put("ds", alarmPhysicalDataSource);

        ShardingRuleConfiguration shardingRuleConfig = new ShardingRuleConfiguration();
        shardingRuleConfig.setDefaultDataSourceName("ds");
        shardingRuleConfig.getTableRuleConfigs().add(buildTableRule("alarm",
                "alarm_id,alarm_cid,device_sn,irms_sn,alarm_beginTime", algorithm));
        shardingRuleConfig.getTableRuleConfigs().add(buildTableRule("alarm_handle",
                "alarm_id,alarm_beginTime", algorithm));
        shardingRuleConfig.getTableRuleConfigs().add(buildTableRule("alarm_electrolytic_cell",
                "alarm_id,alarm_beginTime", algorithm));
        shardingRuleConfig.getBindingTableGroups().add("alarm,alarm_handle,alarm_electrolytic_cell");

        try {
            return ShardingDataSourceFactory.createDataSource(dataSourceMap, shardingRuleConfig, new Properties());
        } catch (SQLException ex) {
            throw new IllegalStateException("创建 hpis-alarm 时间容量分片数据源失败", ex);
        }
    }

    private TableRuleConfiguration buildTableRule(String logicTableName, String shardingColumns,
                                                  AlarmTimeCapacityShardingAlgorithm algorithm) {
        TableRuleConfiguration tableRule = new TableRuleConfiguration(logicTableName, "ds." + logicTableName + "_*");
        tableRule.setTableShardingStrategyConfig(
                new ComplexShardingStrategyConfiguration(shardingColumns, algorithm));
        return tableRule;
    }
}
