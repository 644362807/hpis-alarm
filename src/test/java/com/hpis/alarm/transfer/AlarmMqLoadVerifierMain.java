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
        VerifyResult result = run();
        if (!result.isSuccess()) {
            System.err.println("loadtest failed or timed out, report=" + result.getReport());
            System.exit(2);
        }
        System.out.println("loadtest passed, report=" + result.getReport());
    }

    public static VerifyResult run() throws Exception {
        VerifyOptions options = VerifyOptions.fromSystemProperties();
        Files.createDirectories(options.outputDir);
        Properties sendSummary = loadSendSummary(options.outputDir.resolve("send-summary.properties"));
        options.applySendSummary(sendSummary);
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
        sendSummary = loadSendSummary(options.outputDir.resolve("send-summary.properties"));
        options.applySendSummary(sendSummary);
        boolean success = terminal != null && isSuccess(terminal, options);
        Path report = writeReport(options, sendSummary, snapshots, terminal, verifyStartMillis, verifyEndMillis, success);
        return new VerifyResult(success, report);
    }

    private static Snapshot sample(java.sql.Connection db, VerifyOptions options) throws Exception {
        Snapshot snapshot = new Snapshot();
        snapshot.sampleMillis = System.currentTimeMillis();
        QueueStats queueStats = readQueueStats(options);
        snapshot.queueReady = queueStats.ready;
        snapshot.queueUnacked = queueStats.unacked;
        snapshot.queueConsumers = queueStats.consumers;

        List<String> suffixes = loadSuffixes(db, options.monthKey);
        if (suffixes.isEmpty()) {
            suffixes.add(options.monthKey + "_00");
        }
        for (String suffix : suffixes) {
            if (tableExists(db, "alarm_" + suffix)) {
                CountPair pair = countAlarmTable(db, "alarm_" + suffix, options.alarmCidLike);
                snapshot.alarmRows += pair.total;
                snapshot.closedRows += pair.closed;
                countElectrolyticConsistency(db, "alarm_" + suffix,
                        "alarm_electrolytic_cell_" + suffix, options.alarmCidLike, snapshot);
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
        if (coreDone && options.expectedElectrolyticCount > 0) {
            coreDone = snapshot.electrolyticRows >= options.expectedElectrolyticCount
                    && snapshot.electrolyticMissingRows == 0;
        }
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

    private static void countElectrolyticConsistency(java.sql.Connection db, String alarmTable,
                                                     String electrolyticTable, String alarmCidLike,
                                                     Snapshot snapshot) throws SQLException {
        if (!tableExists(db, electrolyticTable)) {
            return;
        }
        snapshot.electrolyticRows += countLong(db,
                "select count(1) from " + alarmTable + " a join " + electrolyticTable
                        + " c on a.alarm_id = c.alarm_id where a.alarm_cid like ?",
                alarmCidLike);
        snapshot.electrolyticNullAlarmIdRows += countLong(db,
                "select count(1) from " + electrolyticTable + " where alarm_id is null");
        snapshot.electrolyticMissingRows += countLong(db,
                "select count(1) from " + alarmTable + " a left join " + electrolyticTable
                        + " c on a.alarm_id = c.alarm_id where a.alarm_cid like ?"
                        + " and cast(a.scene_type as char) = '2' and c.alarm_id is null",
                alarmCidLike);
    }

    private static long countLong(java.sql.Connection db, String sql, String... params) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setString(i + 1, params[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
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

    private static QueueStats readQueueStats(VerifyOptions options) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(options.mqHost);
        factory.setPort(options.mqPort);
        factory.setUsername(options.mqUsername);
        factory.setPassword(options.mqPassword);
        factory.setVirtualHost(options.mqVirtualHost);
        try (Connection connection = factory.newConnection("hpis-alarm-load-verifier-" + options.runId);
             Channel channel = connection.createChannel()) {
            AMQP.Queue.DeclareOk declareOk = channel.queueDeclarePassive(options.queueName);
            return new QueueStats(declareOk.getMessageCount(), -1L, declareOk.getConsumerCount());
        } catch (Exception ex) {
            return new QueueStats(-1L, -1L, -1);
        }
    }

    private static void printSnapshot(Snapshot snapshot) {
        System.out.println("sample t=" + FILE_TIME.format(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(snapshot.sampleMillis), ZoneId.systemDefault()))
                + ", queueReady=" + snapshot.queueReady
                + ", queueUnacked=" + snapshot.queueUnacked
                + ", queueConsumers=" + snapshot.queueConsumers
                + ", alarmRows=" + snapshot.alarmRows
                + ", closedRows=" + snapshot.closedRows
                + ", ecRows=" + snapshot.electrolyticRows
                + ", ecMissing=" + snapshot.electrolyticMissingRows
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

    private static long longProperty(Properties properties, String key, long defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private static String formatRate(long count, long elapsedMillis) {
        if (elapsedMillis <= 0L) {
            return "unknown";
        }
        return String.format(java.util.Locale.ROOT, "%.2f", count * 1000.0D / elapsedMillis);
    }

    private static Path writeReport(VerifyOptions options, Properties sendSummary, List<Snapshot> snapshots,
                                    Snapshot terminal, long verifyStartMillis, long verifyEndMillis,
                                    boolean success) throws Exception {
        Path report = options.outputDir.resolve("report.md");
        Snapshot max = maxSnapshot(snapshots);
        long sendStartMillis = longProperty(sendSummary, "sendStartMillis", 0L);
        long sendDoneMillis = longProperty(sendSummary, "sendDoneMillis", 0L);
        long sendElapsedMillis = longProperty(sendSummary, "sendElapsedMillis", 0L);
        long sentMessages = longProperty(sendSummary, "sentMessages", 0L);
        long terminalMillis = terminal == null ? verifyEndMillis : terminal.sampleMillis;
        long loopElapsedMillis = sendStartMillis > 0L ? terminalMillis - sendStartMillis : -1L;
        long consumeElapsedMillis = sendDoneMillis > 0L ? terminalMillis - sendDoneMillis : -1L;
        long verifierStartupGapMillis = sendDoneMillis > 0L ? verifyStartMillis - sendDoneMillis : -1L;
        Snapshot firstSnapshot = snapshots.isEmpty() ? null : snapshots.get(0);
        Snapshot queueDrainedSnapshot = firstQueueDrainedSnapshot(snapshots);
        Snapshot dbClosedSnapshot = firstDbClosedSnapshot(snapshots, options);
        long firstObservedElapsedMillis = elapsedAfter(sendDoneMillis, firstSnapshot);
        long queueDrainElapsedMillis = elapsedAfter(sendDoneMillis, queueDrainedSnapshot);
        long dbClosedElapsedMillis = elapsedAfter(sendDoneMillis, dbClosedSnapshot);
        long trueClosedLoopElapsedMillis = verifierStartupGapMillis <= options.sampleIntervalMillis
                ? loopElapsedMillis : -1L;
        try (OutputStreamWriter writer = new OutputStreamWriter(Files.newOutputStream(report), StandardCharsets.UTF_8)) {
            writer.write("# Alarm MQ Load Test Report\n\n");
            writer.write("- Run ID: `" + options.runId + "`\n");
            writer.write("- Result: `" + (success ? "PASS" : "FAIL") + "`\n");
            writer.write("- Scenario: `" + sendSummary.getProperty("scenario", "unknown") + "`\n");
            writer.write("- Order mode: `" + sendSummary.getProperty("orderMode", "unknown") + "`\n");
            writer.write("- Alarm count: `" + options.alarmCount + "`\n");
            writer.write("- Expected stop count: `" + options.expectedStopCount + "`\n");
            writer.write("- Expected electrolytic count: `" + options.expectedElectrolyticCount + "`\n");
            writer.write("- Month key: `" + options.monthKey + "`\n");
            writer.write("- Alarm cid like: `" + options.alarmCidLike + "`\n");
            writer.write("- Send elapsed ms: `" + sendElapsedMillis + "`\n");
            writer.write("- Verify elapsed ms: `" + (verifyEndMillis - verifyStartMillis) + "`\n");
            writer.write("- Consume elapsed after send ms: `" + consumeElapsedMillis + "`\n");
            writer.write("- Closed-loop elapsed ms: `" + loopElapsedMillis + "`\n");
            writer.write("- Verifier startup gap ms: `" + verifierStartupGapMillis + "`\n");
            writer.write("- First observed elapsed after send ms: `" + firstObservedElapsedMillis + "`\n");
            writer.write("- Queue drain elapsed after send ms: `" + queueDrainElapsedMillis + "`\n");
            writer.write("- DB closed elapsed after send ms: `" + dbClosedElapsedMillis + "`\n");
            writer.write("- True closed-loop elapsed ms: `" + trueClosedLoopElapsedMillis + "`\n");
            writer.write("- Send throughput msg/s: `" + formatRate(sentMessages, sendElapsedMillis) + "`\n");
            writer.write("- Closed-loop alarm throughput rows/s: `" + formatRate(terminal == null ? 0L : terminal.alarmRows, loopElapsedMillis) + "`\n");
            writer.write("- True closed-loop alarm throughput rows/s: `" + formatRate(terminal == null ? 0L : terminal.alarmRows, trueClosedLoopElapsedMillis) + "`\n");
            if (trueClosedLoopElapsedMillis < 0L) {
                writer.write("- Timing note: `verifier started too late; true closed-loop timing is not reliable for this run`\n");
            }
            writer.write("\n");

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
            writer.write("- Electrolytic: `alarm_electrolytic_cell.alarm_id` joined by `alarm.alarm_id`, missing rows = 0 when expected\n");
        }
        return report;
    }

    private static Snapshot firstQueueDrainedSnapshot(List<Snapshot> snapshots) {
        for (Snapshot snapshot : snapshots) {
            if (snapshot.queueReady == 0L) {
                return snapshot;
            }
        }
        return null;
    }

    private static Snapshot firstDbClosedSnapshot(List<Snapshot> snapshots, VerifyOptions options) {
        for (Snapshot snapshot : snapshots) {
            if (isDbClosed(snapshot, options)) {
                return snapshot;
            }
        }
        return null;
    }

    private static boolean isDbClosed(Snapshot snapshot, VerifyOptions options) {
        long pending = snapshot.stopEventStatus.getOrDefault("PENDING", 0L);
        long failed = snapshot.stopEventStatus.getOrDefault("FAILED", 0L);
        long applied = snapshot.stopEventStatus.getOrDefault("APPLIED", 0L);
        return snapshot.alarmRows >= options.alarmCount
                && snapshot.closedRows >= options.expectedStopCount
                && applied >= options.expectedStopCount
                && pending == 0L
                && failed == 0L;
    }

    private static long elapsedAfter(long startMillis, Snapshot snapshot) {
        if (startMillis <= 0L || snapshot == null) {
            return -1L;
        }
        return snapshot.sampleMillis - startMillis;
    }

    private static void writeSnapshot(OutputStreamWriter writer, Snapshot snapshot) throws Exception {
        if (snapshot == null) {
            writer.write("- none\n");
            return;
        }
        writer.write("- Queue ready: `" + snapshot.queueReady + "`\n");
        writer.write("- Queue unacked: `" + snapshot.queueUnacked + "` (AMQP passive declare cannot expose this value; -1 means unavailable)\n");
        writer.write("- Queue consumers: `" + snapshot.queueConsumers + "`\n");
        writer.write("- Alarm rows: `" + snapshot.alarmRows + "`\n");
        writer.write("- Closed rows: `" + snapshot.closedRows + "`\n");
        writer.write("- Electrolytic rows joined by alarm_id: `" + snapshot.electrolyticRows + "`\n");
        writer.write("- Electrolytic rows missing for run scene_type=2: `" + snapshot.electrolyticMissingRows + "`\n");
        writer.write("- Electrolytic rows with null alarm_id observed in physical EC tables: `" + snapshot.electrolyticNullAlarmIdRows + "`\n");
        writer.write("- Stop event status: `" + snapshot.stopEventStatus + "`\n");
        writer.write("- Side effect status: `" + snapshot.sideEffectStatus + "`\n");
        writer.write("- Hot route status: `" + snapshot.hotRouteStatus + "`\n");
        writer.write("- Stale route status: `" + snapshot.staleRouteStatus + "`\n");
    }

    private static Snapshot maxSnapshot(List<Snapshot> snapshots) {
        Snapshot max = new Snapshot();
        for (Snapshot snapshot : snapshots) {
            max.queueReady = Math.max(max.queueReady, snapshot.queueReady);
            max.queueUnacked = Math.max(max.queueUnacked, snapshot.queueUnacked);
            max.queueConsumers = Math.max(max.queueConsumers, snapshot.queueConsumers);
            max.alarmRows = Math.max(max.alarmRows, snapshot.alarmRows);
            max.closedRows = Math.max(max.closedRows, snapshot.closedRows);
            max.electrolyticRows = Math.max(max.electrolyticRows, snapshot.electrolyticRows);
            max.electrolyticMissingRows = Math.max(max.electrolyticMissingRows, snapshot.electrolyticMissingRows);
            max.electrolyticNullAlarmIdRows = Math.max(max.electrolyticNullAlarmIdRows, snapshot.electrolyticNullAlarmIdRows);
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

    private static final class QueueStats {
        private final long ready;
        private final long unacked;
        private final int consumers;

        private QueueStats(long ready, long unacked, int consumers) {
            this.ready = ready;
            this.unacked = unacked;
            this.consumers = consumers;
        }
    }

    public static final class VerifyResult {
        private final boolean success;
        private final Path report;

        private VerifyResult(boolean success, Path report) {
            this.success = success;
            this.report = report;
        }

        public boolean isSuccess() {
            return success;
        }

        public Path getReport() {
            return report;
        }
    }

    private static final class Snapshot {
        private long sampleMillis;
        private long queueReady;
        private long queueUnacked = -1L;
        private int queueConsumers;
        private long alarmRows;
        private long closedRows;
        private long electrolyticRows;
        private long electrolyticMissingRows;
        private long electrolyticNullAlarmIdRows;
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
        private int expectedElectrolyticCount;
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
            this.expectedElectrolyticCount = intProperty("alarm.loadtest.expectedElectrolyticCount", -1);
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

        private void applySendSummary(Properties sendSummary) {
            if (expectedElectrolyticCount >= 0) {
                return;
            }
            String value = sendSummary.getProperty("electrolyticCount");
            if (value == null || value.trim().isEmpty()) {
                return;
            }
            expectedElectrolyticCount = Integer.parseInt(value.trim());
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
