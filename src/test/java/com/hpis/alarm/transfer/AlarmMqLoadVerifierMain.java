package com.hpis.alarm.transfer;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.AMQP;

import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Standalone verifier for the hpis-alarm MQ load test.
 *
 * <p>The verifier samples RabbitMQ and MySQL until the current run reaches a
 * terminal state or times out. It intentionally filters by the generated
 * alarm_cid prefix, so existing historical test data does not affect the
 * result.</p>
 */
public class AlarmMqLoadVerifierMain {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        VerifyOptions options = VerifyOptions.fromSystemProperties();
        Files.createDirectories(options.outputDir);
        Properties sendSummary = loadSendSummary(options.outputDir.resolve("send-summary.properties"));
        long verifyStartMillis = System.currentTimeMillis();
        List<Snapshot> snapshots = new ArrayList<>();
        Snapshot terminal = null;

        try (java.sql.Connection db = DriverManager.getConnection(options.jdbcUrl, options.jdbcUsername, options.jdbcPassword)) {
            long deadline = System.currentTimeMillis() + options.timeoutSeconds * 1000L;
            while (System.currentTimeMillis() <= deadline) {
                Snapshot snapshot = sample(db, options);
                snapshots.add(snapshot);
                printSnapshot(snapshot);
                if (isSuccess(snapshot, options)) {
                    terminal = snapshot;
                    break;
                }
                Thread.sleep(options.sampleIntervalMillis);
            }
            if (terminal == null && !snapshots.isEmpty()) {
                terminal = snapshots.get(snapshots.size() - 1);
            }
        }

        long verifyEndMillis = System.currentTimeMillis();
        boolean success = terminal != null && isSuccess(terminal, options);
        Path report = writeReport(options, sendSummary, snapshots, terminal, verifyStartMillis, verifyEndMillis, success);
        if (!success) {
            System.err.println("loadtest failed or timed out, report=" + report);
            System.exit(2);
        }
        System.out.println("loadtest passed, report=" + report);
    }

    private static Snapshot sample(java.sql.Connection db, VerifyOptions options) throws Exception {
        Snapshot snapshot = new Snapshot();
        snapshot.sampleMillis = System.currentTimeMillis();
        snapshot.queueReady = readQueueReady(options);

        List<String> suffixes = loadSuffixes(db, options.monthKey);
        if (suffixes.isEmpty()) {
            suffixes.add(options.monthKey + "_00");
        }
        for (String suffix : suffixes) {
            if (tableExists(db, "alarm_" + suffix)) {
                CountPair pair = countAlarmTable(db, "alarm_" + suffix, options.alarmCidLike);
                snapshot.alarmRows += pair.total;
                snapshot.closedRows += pair.closed;
            }
        }
        snapshot.stopEventStatus.putAll(countStatus(db, "alarm_stop_event", "event_status", options.alarmCidLike));
        snapshot.sideEffectStatus.putAll(countStatus(db, "alarm_stop_side_effect_event", "effect_status", options.alarmCidLike));
        snapshot.hotRouteStatus.putAll(countStatus(db, "alarm_cid_index", "route_status", options.alarmCidLike));
        snapshot.staleRouteStatus.putAll(countStatus(db, "alarm_cid_stale_index", "route_status", options.alarmCidLike));
        return snapshot;
    }

    private static boolean isSuccess(Snapshot snapshot, VerifyOptions options) {
        long pending = snapshot.stopEventStatus.getOrDefault("PENDING", 0L);
        long failed = snapshot.stopEventStatus.getOrDefault("FAILED", 0L);
        long applied = snapshot.stopEventStatus.getOrDefault("APPLIED", 0L);
        boolean coreDone = snapshot.alarmRows >= options.alarmCount
                && snapshot.closedRows >= options.expectedStopCount
                && applied >= options.expectedStopCount
                && pending == 0
                && failed == 0
                && snapshot.queueReady == 0;
        if (!options.requireSideEffects) {
            return coreDone;
        }
        long sidePending = snapshot.sideEffectStatus.getOrDefault("PENDING", 0L);
        long sideFailed = snapshot.sideEffectStatus.getOrDefault("FAILED", 0L);
        return coreDone && sidePending == 0 && sideFailed == 0;
    }

    private static CountPair countAlarmTable(java.sql.Connection db, String tableName, String alarmCidLike) throws SQLException {
        String sql = "select count(1) as total_count, "
                + "sum(case when alarm_endTime is not null then 1 else 0 end) as closed_count "
                + "from " + tableName + " where alarm_cid like ?";
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setString(1, alarmCidLike);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new CountPair(resultSet.getLong("total_count"), resultSet.getLong("closed_count"));
                }
            }
        }
        return new CountPair(0L, 0L);
    }

    private static Map<String, Long> countStatus(java.sql.Connection db, String tableName, String statusColumn,
                                                 String alarmCidLike) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        if (!tableExists(db, tableName)) {
            return result;
        }
        String sql = "select " + statusColumn + " as status_name, count(1) as total_count "
                + "from " + tableName + " where alarm_cid like ? group by " + statusColumn;
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setString(1, alarmCidLike);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.put(resultSet.getString("status_name"), resultSet.getLong("total_count"));
                }
            }
        }
        return result;
    }

    private static List<String> loadSuffixes(java.sql.Connection db, String monthKey) throws SQLException {
        List<String> suffixes = new ArrayList<>();
        if (!tableExists(db, "alarm_shard_slice")) {
            return suffixes;
        }
        try (PreparedStatement statement = db.prepareStatement(
                "select table_suffix from alarm_shard_slice where month_key = ? order by slice_no")) {
            statement.setString(1, monthKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    suffixes.add(resultSet.getString("table_suffix"));
                }
            }
        }
        return suffixes;
    }

    private static boolean tableExists(java.sql.Connection db, String tableName) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(
                "select 1 from information_schema.tables where table_schema = database() and table_name = ? limit 1")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static long readQueueReady(VerifyOptions options) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(options.mqHost);
        factory.setPort(options.mqPort);
        factory.setUsername(options.mqUsername);
        factory.setPassword(options.mqPassword);
        factory.setVirtualHost(options.mqVirtualHost);
        try (Connection connection = factory.newConnection("hpis-alarm-load-verifier-" + options.runId);
             Channel channel = connection.createChannel()) {
            AMQP.Queue.DeclareOk declareOk = channel.queueDeclarePassive(options.queueName);
            return declareOk.getMessageCount();
        } catch (Exception ex) {
            return -1L;
        }
    }

    private static void printSnapshot(Snapshot snapshot) {
        System.out.println("sample t=" + FILE_TIME.format(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(snapshot.sampleMillis), ZoneId.systemDefault()))
                + ", queueReady=" + snapshot.queueReady
                + ", alarmRows=" + snapshot.alarmRows
                + ", closedRows=" + snapshot.closedRows
                + ", stop=" + snapshot.stopEventStatus
                + ", side=" + snapshot.sideEffectStatus);
    }

    private static Properties loadSendSummary(Path path) {
        Properties properties = new Properties();
        if (!Files.exists(path)) {
            return properties;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception ignored) {
            return new Properties();
        }
        return properties;
    }

    private static Path writeReport(VerifyOptions options, Properties sendSummary, List<Snapshot> snapshots,
                                    Snapshot terminal, long verifyStartMillis, long verifyEndMillis,
                                    boolean success) throws Exception {
        Path report = options.outputDir.resolve("report.md");
        Snapshot max = maxSnapshot(snapshots);
        try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(report), StandardCharsets.UTF_8)) {
            writer.write("# Alarm MQ Load Test Report\n\n");
            writer.write("- Run ID: `" + options.runId + "`\n");
            writer.write("- Result: `" + (success ? "PASS" : "FAIL") + "`\n");
            writer.write("- Alarm count: `" + options.alarmCount + "`\n");
            writer.write("- Expected stop count: `" + options.expectedStopCount + "`\n");
            writer.write("- Month key: `" + options.monthKey + "`\n");
            writer.write("- Alarm cid like: `" + options.alarmCidLike + "`\n");
            writer.write("- Send elapsed ms: `" + sendSummary.getProperty("sendElapsedMillis", "unknown") + "`\n");
            writer.write("- Verify elapsed ms: `" + (verifyEndMillis - verifyStartMillis) + "`\n\n");

            writer.write("## Terminal Snapshot\n\n");
            writeSnapshot(writer, terminal);

            writer.write("\n## Max Observed\n\n");
            writeSnapshot(writer, max);

            writer.write("\n## Criteria\n\n");
            writer.write("- `alarmRows >= alarmCount`\n");
            writer.write("- `closedRows >= expectedStopCount`\n");
            writer.write("- `alarm_stop_event.APPLIED >= expectedStopCount`\n");
            writer.write("- `alarm_stop_event.PENDING = 0`\n");
            writer.write("- `alarm_stop_event.FAILED = 0`\n");
            writer.write("- `alarm_queue.ready = 0`\n");
        }
        return report;
    }

    private static void writeSnapshot(OutputStreamWriter writer, Snapshot snapshot) throws Exception {
        if (snapshot == null) {
            writer.write("- none\n");
            return;
        }
        writer.write("- Queue ready: `" + snapshot.queueReady + "`\n");
        writer.write("- Alarm rows: `" + snapshot.alarmRows + "`\n");
        writer.write("- Closed rows: `" + snapshot.closedRows + "`\n");
        writer.write("- Stop event status: `" + snapshot.stopEventStatus + "`\n");
        writer.write("- Side effect status: `" + snapshot.sideEffectStatus + "`\n");
        writer.write("- Hot route status: `" + snapshot.hotRouteStatus + "`\n");
        writer.write("- Stale route status: `" + snapshot.staleRouteStatus + "`\n");
    }

    private static Snapshot maxSnapshot(List<Snapshot> snapshots) {
        Snapshot max = new Snapshot();
        for (Snapshot snapshot : snapshots) {
            max.queueReady = Math.max(max.queueReady, snapshot.queueReady);
            max.alarmRows = Math.max(max.alarmRows, snapshot.alarmRows);
            max.closedRows = Math.max(max.closedRows, snapshot.closedRows);
            mergeMax(max.stopEventStatus, snapshot.stopEventStatus);
            mergeMax(max.sideEffectStatus, snapshot.sideEffectStatus);
            mergeMax(max.hotRouteStatus, snapshot.hotRouteStatus);
            mergeMax(max.staleRouteStatus, snapshot.staleRouteStatus);
        }
        return max;
    }

    private static void mergeMax(Map<String, Long> target, Map<String, Long> source) {
        for (Map.Entry<String, Long> entry : source.entrySet()) {
            target.put(entry.getKey(), Math.max(target.getOrDefault(entry.getKey(), 0L), entry.getValue()));
        }
    }

    private static final class CountPair {
        private final long total;
        private final long closed;

        private CountPair(long total, long closed) {
            this.total = total;
            this.closed = closed;
        }
    }

    private static final class Snapshot {
        private long sampleMillis;
        private long queueReady;
        private long alarmRows;
        private long closedRows;
        private final Map<String, Long> stopEventStatus = new LinkedHashMap<>();
        private final Map<String, Long> sideEffectStatus = new LinkedHashMap<>();
        private final Map<String, Long> hotRouteStatus = new LinkedHashMap<>();
        private final Map<String, Long> staleRouteStatus = new LinkedHashMap<>();
    }

    private static final class VerifyOptions {
        private final String runId;
        private final String alarmIdPrefix;
        private final String alarmCidLike;
        private final Path outputDir;
        private final int alarmCount;
        private final int expectedStopCount;
        private final String monthKey;
        private final long timeoutSeconds;
        private final long sampleIntervalMillis;
        private final boolean requireSideEffects;
        private final String jdbcUrl;
        private final String jdbcUsername;
        private final String jdbcPassword;
        private final String mqHost;
        private final int mqPort;
        private final String mqUsername;
        private final String mqPassword;
        private final String mqVirtualHost;
        private final String queueName;

        private VerifyOptions() {
            this.runId = stringProperty("alarm.loadtest.runId", "AUTO-" + System.currentTimeMillis());
            this.alarmIdPrefix = stringProperty("alarm.loadtest.alarmIdPrefix", runId);
            this.alarmCidLike = "{" + alarmIdPrefix + "-%";
            this.outputDir = Paths.get(stringProperty("alarm.loadtest.outputDir", "target/alarm-loadtest/" + runId));
            this.alarmCount = intProperty("alarm.loadtest.alarmCount", 100);
            this.expectedStopCount = intProperty("alarm.loadtest.stopCount", alarmCount);
            this.monthKey = stringProperty("alarm.loadtest.monthKey", "202511");
            this.timeoutSeconds = longProperty("alarm.loadtest.timeoutSeconds", 300L);
            this.sampleIntervalMillis = longProperty("alarm.loadtest.sampleIntervalMillis", 1000L);
            this.requireSideEffects = booleanProperty("alarm.loadtest.requireSideEffects", false);
            this.jdbcUrl = stringProperty("alarm.loadtest.jdbcUrl", "jdbc:mysql://127.0.0.1:3306/hpis_alarm");
            this.jdbcUsername = stringProperty("alarm.loadtest.jdbcUsername", "root");
            this.jdbcPassword = stringProperty("alarm.loadtest.jdbcPassword", "123456");
            this.mqHost = stringProperty("mq.host", "127.0.0.1");
            this.mqPort = intProperty("mq.port", 5672);
            this.mqUsername = stringProperty("mq.username", "guest");
            this.mqPassword = stringProperty("mq.password", "guest");
            this.mqVirtualHost = stringProperty("mq.virtualHost", "/");
            this.queueName = stringProperty("mq.queue", "alarm_queue");
        }

        private static VerifyOptions fromSystemProperties() {
            return new VerifyOptions();
        }
    }

    private static String stringProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int intProperty(String key, int defaultValue) {
        return Integer.parseInt(stringProperty(key, String.valueOf(defaultValue)));
    }

    private static long longProperty(String key, long defaultValue) {
        return Long.parseLong(stringProperty(key, String.valueOf(defaultValue)));
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        return Boolean.parseBoolean(stringProperty(key, String.valueOf(defaultValue)));
    }
}
