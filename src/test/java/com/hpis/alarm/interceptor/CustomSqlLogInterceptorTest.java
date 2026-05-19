package com.hpis.alarm.interceptor;

import com.hpis.alarm.config.AlarmSqlLogProperties;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class CustomSqlLogInterceptorTest {

    @Test
    public void disabledGlobalSwitchSuppressesSqlInfoLogs() {
        AlarmSqlLogProperties properties = new AlarmSqlLogProperties();
        properties.setEnabled(false);
        CustomSqlLogInterceptor interceptor = new CustomSqlLogInterceptor(properties);

        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmMapper.insertAlarm", 1000L)).isFalse();
    }

    @Test
    public void alarmWriteModeLogsAlarmWritesAndSkipsWorkerSql() {
        AlarmSqlLogProperties properties = new AlarmSqlLogProperties();
        properties.setEnabled(true);
        properties.setMode(AlarmSqlLogProperties.MODE_ALARM_WRITE);
        properties.setSlowMs(200L);
        CustomSqlLogInterceptor interceptor = new CustomSqlLogInterceptor(properties);

        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmMapper.insertAlarm", 5L)).isTrue();
        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmMapper.updateAlarm", 5L)).isTrue();
        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmStopEventMapper.selectPendingBatch", 500L)).isFalse();
        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmMapper.batchStopByAlarmIds", 500L)).isFalse();
    }

    @Test
    public void alarmWriteModeAlsoLogsSlowNonWorkerSql() {
        AlarmSqlLogProperties properties = new AlarmSqlLogProperties();
        properties.setEnabled(true);
        properties.setMode(AlarmSqlLogProperties.MODE_ALARM_WRITE);
        properties.setSlowMs(200L);
        CustomSqlLogInterceptor interceptor = new CustomSqlLogInterceptor(properties);

        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmConfigureMapper.selectAlarmConfigureList", 250L)).isTrue();
        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmConfigureMapper.selectAlarmConfigureList", 50L)).isFalse();
    }

    @Test
    public void allModeKeepsLegacyFullSqlLoggingBehavior() {
        AlarmSqlLogProperties properties = new AlarmSqlLogProperties();
        properties.setEnabled(true);
        properties.setMode(AlarmSqlLogProperties.MODE_ALL);
        CustomSqlLogInterceptor interceptor = new CustomSqlLogInterceptor(properties);

        assertThat(interceptor.shouldLog("com.hpis.alarm.mapper.AlarmStopEventMapper.countPending", 1L)).isTrue();
    }
}
