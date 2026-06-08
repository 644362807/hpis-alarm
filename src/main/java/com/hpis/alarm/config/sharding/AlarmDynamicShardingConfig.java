package com.hpis.alarm.config.sharding;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * hpis-alarm time-capacity sharding datasource configuration.
 *
 * <p>ShardingSphere 4.1.1 stores actualDataNodes in the datasource rule at creation time.
 * This configuration therefore exposes a stable proxy datasource to Spring/MyBatis and lets
 * the proxy swap the inner ShardingSphere datasource when monthly table rules are refreshed.</p>
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
            AlarmShardProperties properties,
            ObjectProvider<AlarmShardingRuleRefreshService> refreshServiceProvider) {
        AlarmMonthlySliceTableManager tableManager =
                new AlarmMonthlySliceTableManager(alarmPhysicalDataSource, properties, refreshServiceProvider);
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
    public AlarmShardingDataSourceFactory alarmShardingDataSourceFactory(
            @Qualifier("alarmPhysicalDataSourceProperties") DataSourceProperties alarmPhysicalDataSourceProperties,
            AlarmMonthlySliceTableManager tableManager,
            AlarmTimeCapacityShardingAlgorithm algorithm) {
        return new AlarmShardingDataSourceFactory(alarmPhysicalDataSourceProperties, tableManager, algorithm);
    }

    @Bean
    @Primary
    public RefreshableAlarmShardingDataSource shardingDataSource(AlarmShardingDataSourceFactory factory,
                                                                 AlarmShardProperties properties) {
        return new RefreshableAlarmShardingDataSource(factory.createDataSource(),
                properties.getRuleRefresh().safeCloseOldDelayMs());
    }

    @Bean
    public AlarmShardingRuleRefreshService alarmShardingRuleRefreshService(
            RefreshableAlarmShardingDataSource shardingDataSource,
            AlarmShardingDataSourceFactory factory,
            AlarmShardProperties properties) {
        return new AlarmShardingRuleRefreshService(shardingDataSource, factory, properties);
    }
}
