package com.hpis.alarm.transfer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manual gray-suite parameters.
 *
 * <p>The runner keeps these parameters as Java fields so an operator can review
 * the whole test intent before starting a shared-environment run from an IDE.</p>
 */
public final class GraySuiteOptions {

    public enum GraySuite {
        SMOKE,
        FUNCTIONAL,
        TARGET_RATE,
        SUSTAINED,
        SAFETY,
        SHARDING
    }

    private final GraySuite suite;
    private final boolean confirmRemoteStub;
    private final boolean confirmFaultInjection;
    private final boolean confirmShardingLimit;
    private final int smokeAlarmCount;
    private final String shardingMonthKey;
    private final String executionId;
    private final Path outputDir;
    private final String mqHost;
    private final int mqPort;
    private final String mqUsername;
    private final String mqPassword;
    private final String mqVirtualHost;
    private final String queueName;
    private final String mqManagementUrl;
    private final boolean requireMqManagement;
    private final String jdbcUrl;
    private final String jdbcUsername;
    private final String jdbcPassword;

    private GraySuiteOptions(Builder builder) {
        this.suite = builder.suite;
        this.confirmRemoteStub = builder.confirmRemoteStub;
        this.confirmFaultInjection = builder.confirmFaultInjection;
        this.confirmShardingLimit = builder.confirmShardingLimit;
        this.smokeAlarmCount = builder.smokeAlarmCount;
        this.shardingMonthKey = builder.shardingMonthKey;
        this.executionId = builder.executionId == null
                ? suite.name() + "-" + System.currentTimeMillis()
                : builder.executionId;
        this.outputDir = builder.outputDir == null
                ? Paths.get("target", "alarm-graytest", executionId)
                : builder.outputDir;
        this.mqHost = builder.mqHost;
        this.mqPort = builder.mqPort;
        this.mqUsername = builder.mqUsername;
        this.mqPassword = builder.mqPassword;
        this.mqVirtualHost = builder.mqVirtualHost;
        this.queueName = builder.queueName;
        this.mqManagementUrl = builder.mqManagementUrl;
        this.requireMqManagement = builder.requireMqManagement;
        this.jdbcUrl = builder.jdbcUrl;
        this.jdbcUsername = builder.jdbcUsername;
        this.jdbcPassword = builder.jdbcPassword;
        validate();
    }

    public static Builder builder() {
        return new Builder();
    }

    private void validate() {
        if (suite == null) {
            throw new IllegalArgumentException("gray suite is required");
        }
        if (!confirmRemoteStub) {
            throw new IllegalArgumentException("shared gray test requires confirmRemoteStub=true");
        }
        if (suite == GraySuite.SAFETY && !confirmFaultInjection) {
            throw new IllegalArgumentException("SAFETY suite requires confirmFaultInjection=true");
        }
        if (suite == GraySuite.SHARDING && !confirmShardingLimit) {
            throw new IllegalArgumentException("SHARDING suite requires confirmShardingLimit=true");
        }
        if (smokeAlarmCount <= 0) {
            throw new IllegalArgumentException("smokeAlarmCount must be > 0");
        }
        if (shardingMonthKey == null || !shardingMonthKey.matches("\\d{6}")) {
            throw new IllegalArgumentException("shardingMonthKey must use yyyyMM");
        }
    }

    public GraySuite getSuite() {
        return suite;
    }

    public boolean isConfirmRemoteStub() {
        return confirmRemoteStub;
    }

    public boolean isConfirmFaultInjection() {
        return confirmFaultInjection;
    }

    public boolean isConfirmShardingLimit() {
        return confirmShardingLimit;
    }

    public int getSmokeAlarmCount() {
        return smokeAlarmCount;
    }

    public String getShardingMonthKey() {
        return shardingMonthKey;
    }

    public String getExecutionId() {
        return executionId;
    }

    public Path getOutputDir() {
        return outputDir;
    }

    public String getMqHost() {
        return mqHost;
    }

    public int getMqPort() {
        return mqPort;
    }

    public String getMqUsername() {
        return mqUsername;
    }

    public String getMqPassword() {
        return mqPassword;
    }

    public String getMqVirtualHost() {
        return mqVirtualHost;
    }

    public String getQueueName() {
        return queueName;
    }

    public String getMqManagementUrl() {
        return mqManagementUrl;
    }

    public boolean isRequireMqManagement() {
        return requireMqManagement;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getJdbcUsername() {
        return jdbcUsername;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public static final class Builder {
        private GraySuite suite = GraySuite.SMOKE;
        private boolean confirmRemoteStub;
        private boolean confirmFaultInjection;
        private boolean confirmShardingLimit;
        private int smokeAlarmCount = 100;
        private String shardingMonthKey = "202609";
        private String executionId;
        private Path outputDir;
        private String mqHost = "127.0.0.1";
        private int mqPort = 5672;
        private String mqUsername = "guest";
        private String mqPassword = "guest";
        private String mqVirtualHost = "/";
        private String queueName = "alarm_queue";
        private String mqManagementUrl = "http://127.0.0.1:15672";
        private boolean requireMqManagement = true;
        private String jdbcUrl = "jdbc:mysql://127.0.0.1:3306/hpis_alarm";
        private String jdbcUsername = "root";
        private String jdbcPassword = "123456";

        public Builder suite(GraySuite suite) {
            this.suite = suite;
            return this;
        }

        public Builder confirmRemoteStub(boolean confirmRemoteStub) {
            this.confirmRemoteStub = confirmRemoteStub;
            return this;
        }

        public Builder confirmFaultInjection(boolean confirmFaultInjection) {
            this.confirmFaultInjection = confirmFaultInjection;
            return this;
        }

        public Builder confirmShardingLimit(boolean confirmShardingLimit) {
            this.confirmShardingLimit = confirmShardingLimit;
            return this;
        }

        public Builder smokeAlarmCount(int smokeAlarmCount) {
            this.smokeAlarmCount = smokeAlarmCount;
            return this;
        }

        public Builder shardingMonthKey(String shardingMonthKey) {
            this.shardingMonthKey = shardingMonthKey;
            return this;
        }

        public Builder executionId(String executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder requireMqManagement(boolean requireMqManagement) {
            this.requireMqManagement = requireMqManagement;
            return this;
        }

        public Builder mqHost(String mqHost) {
            this.mqHost = mqHost;
            return this;
        }

        public Builder mqPort(int mqPort) {
            this.mqPort = mqPort;
            return this;
        }

        public Builder mqUsername(String mqUsername) {
            this.mqUsername = mqUsername;
            return this;
        }

        public Builder mqPassword(String mqPassword) {
            this.mqPassword = mqPassword;
            return this;
        }

        public Builder mqVirtualHost(String mqVirtualHost) {
            this.mqVirtualHost = mqVirtualHost;
            return this;
        }

        public Builder queueName(String queueName) {
            this.queueName = queueName;
            return this;
        }

        public Builder mqManagementUrl(String mqManagementUrl) {
            this.mqManagementUrl = mqManagementUrl;
            return this;
        }

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder jdbcUsername(String jdbcUsername) {
            this.jdbcUsername = jdbcUsername;
            return this;
        }

        public Builder jdbcPassword(String jdbcPassword) {
            this.jdbcPassword = jdbcPassword;
            return this;
        }

        public GraySuiteOptions build() {
            return new GraySuiteOptions(this);
        }
    }
}
