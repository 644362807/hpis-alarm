package com.hpis.alarm.transfer;

import org.junit.Assume;
import org.junit.Test;

/**
 * Manual IDE/Maven entrypoint. Keep RUN_ENABLED=false in committed code.
 */
public class AlarmMqGraySuiteRunnerTest {

    private static final boolean RUN_ENABLED = false;
    private static final GraySuiteOptions.GraySuite SUITE = GraySuiteOptions.GraySuite.SMOKE;

    private static final boolean CONFIRM_REMOTE_STUB = false;
    private static final boolean CONFIRM_FAULT_INJECTION = false;
    private static final boolean CONFIRM_SHARDING_LIMIT = false;

    private static final int SMOKE_ALARM_COUNT = 100;
    private static final String SHARDING_MONTH_KEY = "202609";
    private static final String MQ_HOST = "127.0.0.1";
    private static final int MQ_PORT = 5672;
    private static final String MQ_USERNAME = "guest";
    private static final String MQ_PASSWORD = "guest";
    private static final String MQ_VIRTUAL_HOST = "/";
    private static final String MQ_QUEUE = "alarm_queue";
    private static final String MQ_MANAGEMENT_URL = "http://127.0.0.1:15672";
    private static final String JDBC_URL = "jdbc:mysql://127.0.0.1:3306/hpis_alarm";
    private static final String JDBC_USERNAME = "root";
    private static final String JDBC_PASSWORD = "123456";

    @Test
    public void runManualGraySuite() throws Exception {
        Assume.assumeTrue("manual gray suite is disabled by default", RUN_ENABLED);
        GraySuiteOptions options = GraySuiteOptions.builder()
                .suite(SUITE)
                .confirmRemoteStub(CONFIRM_REMOTE_STUB)
                .confirmFaultInjection(CONFIRM_FAULT_INJECTION)
                .confirmShardingLimit(CONFIRM_SHARDING_LIMIT)
                .smokeAlarmCount(SMOKE_ALARM_COUNT)
                .shardingMonthKey(SHARDING_MONTH_KEY)
                .mqHost(MQ_HOST)
                .mqPort(MQ_PORT)
                .mqUsername(MQ_USERNAME)
                .mqPassword(MQ_PASSWORD)
                .mqVirtualHost(MQ_VIRTUAL_HOST)
                .queueName(MQ_QUEUE)
                .mqManagementUrl(MQ_MANAGEMENT_URL)
                .jdbcUrl(JDBC_URL)
                .jdbcUsername(JDBC_USERNAME)
                .jdbcPassword(JDBC_PASSWORD)
                .build();
        AlarmMqGraySuiteMain.run(options);
    }
}
