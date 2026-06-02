package com.hpis.alarm.transfer;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared-development gray suite. This class is test-only and never clears MQ or business data.
 */
public class AlarmMqGraySuiteMain {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    public static void run(GraySuiteOptions options) throws Exception {
        Files.createDirectories(options.getOutputDir());
        List<RunSpec> specs = planSpecs(options);
        List<RunOutcome> outcomes = new ArrayList<>();
        for (RunSpec spec : specs) {
            preflight(options);
            RunOutcome outcome = runOne(options, spec);
            outcomes.add(outcome);
            writeSummary(options, outcomes);
            if (!outcome.success) {
                throw new IllegalStateException("gray suite stopped after failed run " + spec.runName
                        + ", report=" + outcome.report);
            }
        }
        writeSummary(options, outcomes);
    }

    static List<RunSpec> planSpecs(GraySuiteOptions options) {
        switch (options.getSuite()) {
            case SMOKE:
                return normalMatrix("SMOKE", options.getSmokeAlarmCount(), false, false);
            case FUNCTIONAL:
                return normalMatrix("FUNCTIONAL", 2000, true, false);
            case TARGET_RATE:
                List<RunSpec> target = normalMatrix("TARGET-WARM", 10000, false, false);
                target.addAll(normalMatrix("TARGET-FORMAL", 10000, false, false));
                return target;
            case SUSTAINED:
                return Arrays.asList(new RunSpec("SUSTAINED-MIXED-ALTERNATE", "MIXED", "ALTERNATE",
                        100000, "NORMAL", 20000L, null));
            case SAFETY:
                return safetyMatrix();
            case SHARDING:
                return Arrays.asList(new RunSpec("SHARDING-MIXED-ALTERNATE", "MIXED", "ALTERNATE",
                        100000, "NORMAL", 0L, options.getShardingMonthKey()));
            default:
                throw new IllegalArgumentException("unsupported suite " + options.getSuite());
        }
    }

    private static List<RunSpec> normalMatrix(String prefix, int count, boolean bothOrders, boolean unused) {
        List<RunSpec> specs = new ArrayList<>();
        List<String> orders = bothOrders
                ? Arrays.asList("START_THEN_STOP", "ALTERNATE")
                : Arrays.asList("ALTERNATE");
        for (String scenario : Arrays.asList("GENERAL", "ELECTROLYTIC", "MIXED")) {
            for (String order : orders) {
                specs.add(new RunSpec(prefix + "-" + scenario + "-" + order, scenario, order,
                        count, "NORMAL", 0L, null));
            }
        }
        return specs;
    }

    private static List<RunSpec> safetyMatrix() {
        return Arrays.asList(
                new RunSpec("SAFETY-DUPLICATE-START", "GENERAL", "ALTERNATE", 20, "DUPLICATE_START", 0L, null),
                new RunSpec("SAFETY-DUPLICATE-STOP", "GENERAL", "ALTERNATE", 20, "DUPLICATE_STOP", 0L, null),
                new RunSpec("SAFETY-STOP-BEFORE-START", "GENERAL", "ALTERNATE", 20, "STOP_BEFORE_START", 0L, null),
                new RunSpec("SAFETY-EC-MISSING-FIELD", "ELECTROLYTIC", "ALTERNATE", 20, "ELECTROLYTIC_MISSING_FIELD", 0L, null),
                new RunSpec("SAFETY-STOP-MISSING-ID", "GENERAL", "ALTERNATE", 20, "INVALID_STOP_MISSING_ALARM_ID", 0L, null),
                new RunSpec("SAFETY-DISCONNECT-DUPLICATE", "MIXED", "ALTERNATE", 100, "DISCONNECT_DUPLICATE", 0L, null));
    }

    private static void preflight(GraySuiteOptions options) throws Exception {
        AlarmMqGrayQueueProbe.QueueStats stats = AlarmMqGrayQueueProbe.read(options);
        if (stats.getReady() != 0L || stats.getUnacked() != 0L) {
            throw new IllegalStateException("alarm_queue is not empty, ready=" + stats.getReady()
                    + ", unacked=" + stats.getUnacked() + "; gray suite never clears shared MQ");
        }
        if (stats.getConsumers() <= 0) {
            throw new IllegalStateException("alarm_queue has no active hpis-alarm consumers");
        }
    }

    private static RunOutcome runOne(GraySuiteOptions options, RunSpec spec) throws Exception {
        String runId = options.getExecutionId() + "-" + spec.runName + "-" + System.currentTimeMillis();
        Path outputDir = options.getOutputDir().resolve(runId);
        String monthKey = spec.monthKey == null ? LocalDate.now().format(MONTH) : spec.monthKey;
        String monthStart = monthKey.substring(0, 4) + "-" + monthKey.substring(4, 6) + "-01";
        try (SystemPropertiesScope scope = new SystemPropertiesScope()) {
            scope.set("alarm.loadtest.runId", runId);
            scope.set("alarm.mq.send.runId", runId);
            scope.set("alarm.loadtest.outputDir", outputDir.toString());
            scope.set("alarm.mq.send.outputDir", outputDir.toString());
            scope.set("alarm.loadtest.alarmCount", String.valueOf(spec.alarmCount));
            scope.set("alarm.mq.send.alarmCount", String.valueOf(spec.alarmCount));
            scope.set("alarm.mq.send.stopRatio", "1.0");
            scope.set("alarm.mq.send.scenario", spec.scenario);
            scope.set("alarm.mq.send.orderMode", spec.orderMode);
            scope.set("alarm.mq.send.faultMode", spec.faultMode);
            scope.set("alarm.mq.send.messagesPerMinute", String.valueOf(spec.messagesPerMinute));
            scope.set("alarm.mq.send.alarmStartTime", monthStart + " 10:00:00");
            scope.set("alarm.mq.send.alarmEndTime", monthStart + " 10:20:00");
            scope.set("alarm.mq.send.stopStartTime", monthStart + " 10:00:01");
            scope.set("alarm.mq.send.stopEndTime", monthStart + " 10:30:00");
            scope.set("alarm.loadtest.monthKey", monthKey);
            scope.set("alarm.loadtest.timeoutSeconds", spec.alarmCount >= 100000 ? "1800" : "300");
            scope.set("alarm.loadtest.mqManagementUrl", options.getMqManagementUrl());
            scope.set("mq.host", options.getMqHost());
            scope.set("mq.port", String.valueOf(options.getMqPort()));
            scope.set("mq.username", options.getMqUsername());
            scope.set("mq.password", options.getMqPassword());
            scope.set("mq.virtualHost", options.getMqVirtualHost());
            scope.set("mq.queue", options.getQueueName());
            scope.set("alarm.loadtest.jdbcUrl", options.getJdbcUrl());
            scope.set("alarm.loadtest.jdbcUsername", options.getJdbcUsername());
            scope.set("alarm.loadtest.jdbcPassword", options.getJdbcPassword());
            AlarmMqLoadVerifierMain.VerifyResult result = AlarmMqLoadOrchestratorMain.run(new String[0]);
            return new RunOutcome(spec, result.isSuccess(), result.getReport());
        }
    }

    private static void writeSummary(GraySuiteOptions options, List<RunOutcome> outcomes) throws Exception {
        Path summary = options.getOutputDir().resolve("summary.md");
        try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(summary), StandardCharsets.UTF_8)) {
            writer.write("# Alarm MQ Gray Suite Summary\n\n");
            writer.write("- Execution ID: `" + options.getExecutionId() + "`\n");
            writer.write("- Suite: `" + options.getSuite() + "`\n");
            writer.write("- Remote stub manually confirmed: `" + options.isConfirmRemoteStub() + "`\n\n");
            writer.write("| Run | Scenario | Order | Fault mode | Alarm count | Result | Report |\n");
            writer.write("|---|---|---|---|---:|---|---|\n");
            for (RunOutcome outcome : outcomes) {
                writer.write("| `" + outcome.spec.runName + "` | `" + outcome.spec.scenario + "` | `"
                        + outcome.spec.orderMode + "` | `" + outcome.spec.faultMode + "` | "
                        + outcome.spec.alarmCount + " | `" + (outcome.success ? "PASS" : "FAIL")
                        + "` | `" + outcome.report + "` |\n");
            }
        }
    }

    static final class RunSpec {
        private final String runName;
        private final String scenario;
        private final String orderMode;
        private final int alarmCount;
        private final String faultMode;
        private final long messagesPerMinute;
        private final String monthKey;

        RunSpec(String runName, String scenario, String orderMode, int alarmCount, String faultMode,
                long messagesPerMinute, String monthKey) {
            this.runName = runName;
            this.scenario = scenario;
            this.orderMode = orderMode;
            this.alarmCount = alarmCount;
            this.faultMode = faultMode;
            this.messagesPerMinute = messagesPerMinute;
            this.monthKey = monthKey;
        }

        String getRunName() {
            return runName;
        }

        String getFaultMode() {
            return faultMode;
        }
    }

    private static final class RunOutcome {
        private final RunSpec spec;
        private final boolean success;
        private final Path report;

        private RunOutcome(RunSpec spec, boolean success, Path report) {
            this.spec = spec;
            this.success = success;
            this.report = report;
        }
    }

    private static final class SystemPropertiesScope implements AutoCloseable {
        private final Map<String, String> original = new LinkedHashMap<>();

        private void set(String key, String value) {
            if (!original.containsKey(key)) {
                original.put(key, System.getProperty(key));
            }
            System.setProperty(key, value);
        }

        @Override
        public void close() {
            for (Map.Entry<String, String> entry : original.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
