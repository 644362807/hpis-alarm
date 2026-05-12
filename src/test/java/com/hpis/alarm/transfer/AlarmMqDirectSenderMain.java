package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Standalone MQ load sender for hpis-alarm.
 *
 * <p>The sender is intentionally placed under src/test/java. It does not start
 * Spring and does not call service APIs. It sends real RabbitMQ messages with
 * the same envelope used by hp_access: dataSync -> cmdData -> rawData.</p>
 *
 * <p>Every run writes a manifest and a send-summary file. The verifier uses the
 * run id embedded in alarm_cid to count only the current run, so pressure tests
 * can be repeated without cleaning historical test data.</p>
 */
public class AlarmMqDirectSenderMain {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int OPER_CODE_ALARM_PUSH = 259;
    private static final int OPER_CODE_ALARM_STOP = 260;

    public static void main(String[] args) throws Exception {
        SenderOptions options = SenderOptions.fromSystemProperties();
        List<GeneratedAlarm> alarms = generateAlarms(options);
        List<GeneratedMqMessage> messages = generateMessages(options, alarms);
        validateGeneratedData(options, alarms, messages);
        writeManifest(options, messages);

        long sendStartMillis = System.currentTimeMillis();
        long startDoneMillis = 0L;
        long stopStartMillis = 0L;
        long sendDoneMillis = 0L;

        if (options.dryRun) {
            printDryRunMessages(options, messages);
            writeSummary(options, sendStartMillis, sendStartMillis, sendStartMillis, System.currentTimeMillis(), 0L);
            return;
        }

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(options.host);
        factory.setPort(options.port);
        factory.setUsername(options.username);
        factory.setPassword(options.password);
        factory.setVirtualHost(options.virtualHost);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);

        try (Connection connection = factory.newConnection("hpis-alarm-direct-sender-" + options.runId);
             Channel channel = connection.createChannel()) {
            if (options.declareQueue) {
                channel.queueDeclare(options.queueName, options.durableQueue, false, false, null);
            }
            AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                    .contentType("application/json")
                    .contentEncoding(StandardCharsets.UTF_8.name())
                    .deliveryMode(options.durableQueue ? 2 : 1)
                    .build();

            List<GeneratedMqMessage> sendList = buildActualSendList(options, messages);
            long firstSendNanos = System.nanoTime();
            long sendIntervalNanos = options.getSendIntervalNanos();
            long sent = 0L;
            boolean stopStarted = false;

            for (GeneratedMqMessage message : sendList) {
                if (!stopStarted && message.operCode == OPER_CODE_ALARM_STOP) {
                    startDoneMillis = System.currentTimeMillis();
                    stopStartMillis = startDoneMillis;
                    stopStarted = true;
                    if (options.orderMode == SendOrderMode.START_THEN_STOP && options.stopQueueDelayMillis > 0) {
                        System.out.println("start messages sent, wait " + options.stopQueueDelayMillis + " ms before stop messages");
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(options.stopQueueDelayMillis));
                        stopStartMillis = System.currentTimeMillis();
                    }
                }
                waitForSendPermit(firstSendNanos, sent, sendIntervalNanos);
                byte[] body = JSON.toJSONString(message.payload).getBytes(StandardCharsets.UTF_8);
                channel.basicPublish("", options.queueName, properties, body);
                sent++;
                if (sent % options.getProgressEvery() == 0 || sent == sendList.size()) {
                    System.out.println("sent MQ messages " + sent + "/" + sendList.size());
                }
            }
            if (!stopStarted) {
                startDoneMillis = System.currentTimeMillis();
                stopStartMillis = startDoneMillis;
            }
            sendDoneMillis = System.currentTimeMillis();
            writeSummary(options, sendStartMillis, startDoneMillis, stopStartMillis, sendDoneMillis, sent);
        }

        System.out.println("send done: runId=" + options.runId
                + ", queue=" + options.queueName
                + ", alarmCount=" + options.alarmCount
                + ", stopCount=" + options.getExpectedStopCount()
                + ", orderMode=" + options.orderMode
                + ", elapsedMs=" + (sendDoneMillis - sendStartMillis));
    }

    private static List<GeneratedMqMessage> buildActualSendList(SenderOptions options, List<GeneratedMqMessage> messages) {
        List<GeneratedMqMessage> startMessages = new ArrayList<>();
        List<GeneratedMqMessage> stopMessages = new ArrayList<>();
        for (GeneratedMqMessage message : messages) {
            if (message.operCode == OPER_CODE_ALARM_PUSH) {
                startMessages.add(message);
            } else {
                stopMessages.add(message);
            }
        }
        startMessages.sort(Comparator.comparing(GeneratedMqMessage::getSendTime));
        stopMessages.sort(Comparator.comparing(GeneratedMqMessage::getSendTime));

        if (options.orderMode == SendOrderMode.START_THEN_STOP) {
            List<GeneratedMqMessage> result = new ArrayList<>(messages.size());
            result.addAll(startMessages);
            result.addAll(stopMessages);
            return result;
        }
        if (options.orderMode == SendOrderMode.ALTERNATE) {
            return alternateMessages(startMessages, stopMessages);
        }
        List<GeneratedMqMessage> ordered = new ArrayList<>(messages);
        ordered.sort(Comparator.comparing(GeneratedMqMessage::getSendTime)
                .thenComparing(GeneratedMqMessage::getOperCode));
        return ordered;
    }

    private static List<GeneratedAlarm> generateAlarms(SenderOptions options) {
        Random random = new Random(options.seed);
        List<GeneratedAlarm> alarms = new ArrayList<>();
        for (int i = 0; i < options.alarmCount; i++) {
            LocalDateTime alarmTime = randomTime(random, options.alarmStartTime, options.alarmEndTime);
            String deviceSn = options.deviceSns.get(random.nextInt(options.deviceSns.size()));
            String gatewaySn = options.gatewaySns.get(random.nextInt(options.gatewaySns.size()));
            String alarmId = "{" + options.alarmIdPrefix + "-" + toFixedBase36(i, 5) + "}";
            alarms.add(new GeneratedAlarm(alarmId, deviceSn, gatewaySn, alarmTime));
        }
        return alarms;
    }

    private static List<GeneratedMqMessage> generateMessages(SenderOptions options, List<GeneratedAlarm> alarms) {
        Random random = new Random(options.seed * 31 + 7);
        List<GeneratedMqMessage> messages = new ArrayList<>();
        for (GeneratedAlarm alarm : alarms) {
            messages.add(new GeneratedMqMessage(alarm.alarmTime, OPER_CODE_ALARM_PUSH,
                    buildAlarmStartMessage(alarm, options), alarm.alarmId, alarm.alarmTime));
        }
        List<GeneratedAlarm> stopCandidates = new ArrayList<>(alarms);
        Collections.shuffle(stopCandidates, random);
        for (int i = 0; i < options.getExpectedStopCount(); i++) {
            GeneratedAlarm alarm = stopCandidates.get(i);
            LocalDateTime stopTime = randomStopTimeAfterAlarm(random, alarm.alarmTime,
                    options.stopStartTime, options.stopEndTime);
            messages.add(new GeneratedMqMessage(stopTime, OPER_CODE_ALARM_STOP,
                    buildAlarmStopMessage(alarm, stopTime, options), alarm.alarmId, alarm.alarmTime));
        }
        return messages;
    }

    private static List<GeneratedMqMessage> alternateMessages(List<GeneratedMqMessage> startMessages,
                                                             List<GeneratedMqMessage> stopMessages) {
        LinkedHashMap<String, GeneratedMqMessage> stopByAlarmId = new LinkedHashMap<>();
        for (GeneratedMqMessage stopMessage : stopMessages) {
            stopByAlarmId.put(stopMessage.alarmId, stopMessage);
        }
        List<GeneratedMqMessage> result = new ArrayList<>(startMessages.size() + stopMessages.size());
        for (GeneratedMqMessage startMessage : startMessages) {
            result.add(startMessage);
            GeneratedMqMessage stopMessage = stopByAlarmId.remove(startMessage.alarmId);
            if (stopMessage != null) {
                result.add(stopMessage);
            }
        }
        result.addAll(stopByAlarmId.values());
        return result;
    }

    private static JSONObject buildAlarmStartMessage(GeneratedAlarm alarm, SenderOptions options) {
        JSONObject rawData = new JSONObject(new LinkedHashMap<String, Object>());
        rawData.put("alarmDegree", options.alarmDegree);
        rawData.put("alarmId", alarm.alarmId);
        rawData.put("alarmType", options.alarmType);
        rawData.put("cameraType", options.cameraType);
        rawData.put("deviceSn", alarm.deviceSn);
        rawData.put("gatewaySn", alarm.gatewaySn);
        rawData.put("sceneType", options.sceneType);
        rawData.put("time", alarm.alarmTime.format(FORMATTER));
        return buildEnvelope(alarm.gatewaySn, OPER_CODE_ALARM_PUSH, rawData, options);
    }

    private static JSONObject buildAlarmStopMessage(GeneratedAlarm alarm, LocalDateTime stopTime, SenderOptions options) {
        JSONObject rawData = new JSONObject(new LinkedHashMap<String, Object>());
        rawData.put("alarmId", alarm.alarmId);
        rawData.put("time", stopTime.format(FORMATTER));
        return buildEnvelope(alarm.gatewaySn, OPER_CODE_ALARM_STOP, rawData, options);
    }

    private static JSONObject buildEnvelope(String gatewaySn, int operCode, JSONObject rawData, SenderOptions options) {
        JSONObject cmdData = new JSONObject(new LinkedHashMap<String, Object>());
        cmdData.put("confItems", options.confItems);
        cmdData.put("deviceSn", gatewaySn);
        cmdData.put("operCode", operCode);
        cmdData.put("rawData", rawData);
        cmdData.put("version", 1);
        JSONObject envelope = new JSONObject(new LinkedHashMap<String, Object>());
        envelope.put("cmd", "dataSync");
        envelope.put("cmdData", cmdData);
        envelope.put("cmdSeq", options.cmdSeq);
        envelope.put("servId", options.servId);
        envelope.put("times", options.times);
        return envelope;
    }

    private static void validateGeneratedData(SenderOptions options, List<GeneratedAlarm> alarms,
                                              List<GeneratedMqMessage> messages) {
        Set<String> alarmIds = new HashSet<>();
        LinkedHashMap<String, LocalDateTime> alarmTimeMap = new LinkedHashMap<>();
        for (GeneratedAlarm alarm : alarms) {
            if (!alarmIds.add(alarm.alarmId)) {
                throw new IllegalStateException("duplicated alarmId: " + alarm.alarmId);
            }
            if (alarm.alarmId.length() > options.maxAlarmCidLength) {
                throw new IllegalStateException("alarmId length exceeds column limit, alarmId="
                        + alarm.alarmId + ", length=" + alarm.alarmId.length()
                        + ", max=" + options.maxAlarmCidLength);
            }
            alarmTimeMap.put(alarm.alarmId, alarm.alarmTime);
        }
        long startCount = messages.stream().filter(message -> message.operCode == OPER_CODE_ALARM_PUSH).count();
        long stopCount = messages.stream().filter(message -> message.operCode == OPER_CODE_ALARM_STOP).count();
        if (startCount != options.alarmCount) {
            throw new IllegalStateException("start count mismatch, expected=" + options.alarmCount + ", actual=" + startCount);
        }
        if (stopCount != options.getExpectedStopCount()) {
            throw new IllegalStateException("stop count mismatch, expected=" + options.getExpectedStopCount() + ", actual=" + stopCount);
        }
        for (GeneratedMqMessage message : messages) {
            if (message.operCode == OPER_CODE_ALARM_STOP
                    && !message.sendTime.isAfter(alarmTimeMap.get(message.alarmId))) {
                throw new IllegalStateException("stop time must be after alarm time, alarmId=" + message.alarmId);
            }
        }
    }

    private static void writeManifest(SenderOptions options, List<GeneratedMqMessage> messages) throws Exception {
        Files.createDirectories(options.outputDir);
        Path manifestPath = options.outputDir.resolve("manifest.jsonl");
        try (BufferedWriter writer = Files.newBufferedWriter(manifestPath, StandardCharsets.UTF_8)) {
            for (GeneratedMqMessage message : buildActualSendList(options, messages)) {
                JSONObject row = new JSONObject(new LinkedHashMap<String, Object>());
                row.put("runId", options.runId);
                row.put("alarmId", message.alarmId);
                row.put("operCode", message.operCode);
                row.put("sendTime", message.sendTime.format(FORMATTER));
                row.put("alarmTime", message.alarmTime.format(FORMATTER));
                writer.write(row.toJSONString());
                writer.newLine();
            }
        }
    }

    private static void writeSummary(SenderOptions options, long sendStartMillis, long startDoneMillis,
                                     long stopStartMillis, long sendDoneMillis, long sent) throws Exception {
        Files.createDirectories(options.outputDir);
        Properties properties = new Properties();
        properties.setProperty("runId", options.runId);
        properties.setProperty("alarmIdPrefix", options.alarmIdPrefix);
        properties.setProperty("alarmCount", String.valueOf(options.alarmCount));
        properties.setProperty("stopCount", String.valueOf(options.getExpectedStopCount()));
        properties.setProperty("orderMode", options.orderMode.name());
        properties.setProperty("sentMessages", String.valueOf(sent));
        properties.setProperty("sendStartMillis", String.valueOf(sendStartMillis));
        properties.setProperty("startDoneMillis", String.valueOf(startDoneMillis));
        properties.setProperty("stopStartMillis", String.valueOf(stopStartMillis));
        properties.setProperty("sendDoneMillis", String.valueOf(sendDoneMillis));
        properties.setProperty("sendElapsedMillis", String.valueOf(sendDoneMillis - sendStartMillis));
        try (OutputStreamWriter writer = new OutputStreamWriter(
                Files.newOutputStream(options.outputDir.resolve("send-summary.properties")), StandardCharsets.UTF_8)) {
            properties.store(writer, "hpis alarm mq loadtest send summary");
        }
    }

    private static void printDryRunMessages(SenderOptions options, List<GeneratedMqMessage> messages) {
        List<GeneratedMqMessage> sendList = buildActualSendList(options, messages);
        int count = Math.min(options.previewCount, sendList.size());
        System.out.println("dryRun=true, preview messages=" + count + ", total=" + sendList.size());
        for (int i = 0; i < count; i++) {
            System.out.println(JSON.toJSONString(sendList.get(i).payload));
        }
    }

    private static LocalDateTime randomStopTimeAfterAlarm(Random random, LocalDateTime alarmTime,
                                                          LocalDateTime stopStartTime, LocalDateTime stopEndTime) {
        LocalDateTime lowerBound = alarmTime.isAfter(stopStartTime) ? alarmTime.plusSeconds(1) : stopStartTime;
        if (lowerBound.isAfter(stopEndTime)) {
            return alarmTime.plusMinutes(1);
        }
        return randomTime(random, lowerBound, stopEndTime);
    }

    private static LocalDateTime randomTime(Random random, LocalDateTime startTime, LocalDateTime endTime) {
        long seconds = Math.max(0, Duration.between(startTime, endTime).getSeconds());
        if (seconds == 0) {
            return startTime;
        }
        return startTime.plusSeconds(nextLong(random, seconds + 1));
    }

    private static long nextLong(Random random, long bound) {
        long bits;
        long value;
        do {
            bits = random.nextLong() & Long.MAX_VALUE;
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0L);
        return value;
    }

    private static String toFixedBase36(int value, int minLength) {
        String text = Integer.toString(value, 36);
        StringBuilder builder = new StringBuilder();
        for (int i = text.length(); i < minLength; i++) {
            builder.append('0');
        }
        return builder.append(text).toString();
    }

    private static String compactAlarmIdPrefix(String runId) {
        String digits = runId == null ? "" : runId.replaceAll("\\D", "");
        if (digits.length() > 12) {
            digits = digits.substring(digits.length() - 12);
        }
        if (digits.isEmpty()) {
            digits = Long.toString(System.currentTimeMillis() % 1_000_000_000_000L);
        }
        String seed = Long.toString((System.nanoTime() & 0xFFFFFL), 36);
        return "A" + digits + seed;
    }

    private static void waitForSendPermit(long firstSendNanos, long sent, long sendIntervalNanos) {
        if (sendIntervalNanos <= 0 || sent == 0) {
            return;
        }
        long targetNanos = firstSendNanos + sent * sendIntervalNanos;
        long waitNanos = targetNanos - System.nanoTime();
        if (waitNanos > 0) {
            LockSupport.parkNanos(waitNanos);
        }
    }

    private enum SendOrderMode {
        START_THEN_STOP,
        ALTERNATE,
        TIME_ORDER
    }

    private static final class GeneratedAlarm {
        private final String alarmId;
        private final String deviceSn;
        private final String gatewaySn;
        private final LocalDateTime alarmTime;

        private GeneratedAlarm(String alarmId, String deviceSn, String gatewaySn, LocalDateTime alarmTime) {
            this.alarmId = alarmId;
            this.deviceSn = deviceSn;
            this.gatewaySn = gatewaySn;
            this.alarmTime = alarmTime;
        }
    }

    private static final class GeneratedMqMessage {
        private final LocalDateTime sendTime;
        private final int operCode;
        private final JSONObject payload;
        private final String alarmId;
        private final LocalDateTime alarmTime;

        private GeneratedMqMessage(LocalDateTime sendTime, int operCode, JSONObject payload,
                                   String alarmId, LocalDateTime alarmTime) {
            this.sendTime = sendTime;
            this.operCode = operCode;
            this.payload = payload;
            this.alarmId = alarmId;
            this.alarmTime = alarmTime;
        }

        private LocalDateTime getSendTime() {
            return sendTime;
        }

        private int getOperCode() {
            return operCode;
        }
    }

    private static final class SenderOptions {
        private final String runId;
        private final String alarmIdPrefix;
        private final Path outputDir;
        private final String host;
        private final int port;
        private final String username;
        private final String password;
        private final String virtualHost;
        private final String queueName;
        private final boolean declareQueue;
        private final boolean durableQueue;
        private final int alarmCount;
        private final double stopRatio;
        private final LocalDateTime alarmStartTime;
        private final LocalDateTime alarmEndTime;
        private final LocalDateTime stopStartTime;
        private final LocalDateTime stopEndTime;
        private final List<String> deviceSns;
        private final List<String> gatewaySns;
        private final int alarmDegree;
        private final int alarmType;
        private final int cameraType;
        private final int sceneType;
        private final int confItems;
        private final int cmdSeq;
        private final String servId;
        private final long times;
        private final long seed;
        private final long intervalMillis;
        private final long messagesPerMinute;
        private final long progressEvery;
        private final long stopQueueDelayMillis;
        private final int maxAlarmCidLength;
        private final SendOrderMode orderMode;
        private final boolean dryRun;
        private final int previewCount;

        private SenderOptions() {
            this.runId = stringProperty("alarm.mq.send.runId", "AUTO-" + System.currentTimeMillis());
            this.alarmIdPrefix = stringProperty("alarm.mq.send.alarmIdPrefix", compactAlarmIdPrefix(runId));
            this.outputDir = Paths.get(stringProperty("alarm.mq.send.outputDir",
                    "target/alarm-loadtest/" + runId));
            this.host = stringProperty("mq.host", "127.0.0.1");
            this.port = intProperty("mq.port", 5672);
            this.username = stringProperty("mq.username", "guest");
            this.password = stringProperty("mq.password", "guest");
            this.virtualHost = stringProperty("mq.virtualHost", "/");
            this.queueName = stringProperty("mq.queue", "alarm_queue");
            this.declareQueue = booleanProperty("mq.declareQueue", true);
            this.durableQueue = booleanProperty("mq.durableQueue", true);
            this.alarmCount = intProperty("alarm.mq.send.alarmCount", 100);
            this.stopRatio = doubleProperty("alarm.mq.send.stopRatio", 0.7D);
            this.alarmStartTime = timeProperty("alarm.mq.send.alarmStartTime", "2025-11-01 23:50:00");
            this.alarmEndTime = timeProperty("alarm.mq.send.alarmEndTime", "2025-11-01 23:59:50");
            this.stopStartTime = timeProperty("alarm.mq.send.stopStartTime", "2025-11-01 23:53:00");
            this.stopEndTime = timeProperty("alarm.mq.send.stopEndTime", "2025-11-01 23:59:55");
            this.deviceSns = listProperty("alarm.mq.send.deviceSns", "HM-TD2068T-5/Q20250724AACHEA4925334");
            this.gatewaySns = listProperty("alarm.mq.send.gatewaySns", "5b6bebaff7aed77ae423af7e4065ef91");
            this.alarmDegree = intProperty("alarm.mq.send.alarmDegree", 3);
            this.alarmType = intProperty("alarm.mq.send.alarmType", 6);
            this.cameraType = intProperty("alarm.mq.send.cameraType", 1);
            this.sceneType = intProperty("alarm.mq.send.sceneType", 1);
            this.confItems = intProperty("alarm.mq.send.confItems", 1000);
            this.cmdSeq = intProperty("alarm.mq.send.cmdSeq", 3);
            this.servId = stringProperty("alarm.mq.send.servId", "hp_access");
            this.times = longProperty("alarm.mq.send.times", 813376452L);
            this.seed = longProperty("alarm.mq.send.seed", System.currentTimeMillis());
            this.intervalMillis = longProperty("alarm.mq.send.intervalMillis", 0L);
            this.messagesPerMinute = longProperty("alarm.mq.send.messagesPerMinute", 0L);
            this.progressEvery = longProperty("alarm.mq.send.progressEvery", 1000L);
            this.stopQueueDelayMillis = longProperty("alarm.mq.send.stopQueueDelayMillis", 10000L);
            this.maxAlarmCidLength = intProperty("alarm.mq.send.maxAlarmCidLength", 38);
            this.orderMode = orderModeProperty("alarm.mq.send.orderMode", SendOrderMode.START_THEN_STOP);
            this.dryRun = booleanProperty("alarm.mq.send.dryRun", false);
            this.previewCount = intProperty("alarm.mq.send.previewCount", 5);
            validate();
        }

        private static SenderOptions fromSystemProperties() {
            return new SenderOptions();
        }

        private void validate() {
            if (alarmCount < 0) {
                throw new IllegalArgumentException("alarmCount must be >= 0");
            }
            if (stopRatio < 0D || stopRatio > 1D) {
                throw new IllegalArgumentException("stopRatio must be between 0 and 1");
            }
            if (alarmStartTime.isAfter(alarmEndTime)) {
                throw new IllegalArgumentException("alarmStartTime must not be after alarmEndTime");
            }
            if (stopStartTime.isAfter(stopEndTime)) {
                throw new IllegalArgumentException("stopStartTime must not be after stopEndTime");
            }
        }

        private int getExpectedStopCount() {
            return Math.min(alarmCount, (int) Math.round(alarmCount * stopRatio));
        }

        private long getSendIntervalNanos() {
            long intervalByMillis = intervalMillis <= 0 ? 0 : TimeUnit.MILLISECONDS.toNanos(intervalMillis);
            long intervalByMinute = messagesPerMinute <= 0 ? 0 : (long) Math.ceil(60_000_000_000D / messagesPerMinute);
            return Math.max(intervalByMillis, intervalByMinute);
        }

        private long getProgressEvery() {
            return progressEvery <= 0 ? Long.MAX_VALUE : progressEvery;
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

    private static double doubleProperty(String key, double defaultValue) {
        return Double.parseDouble(stringProperty(key, String.valueOf(defaultValue)));
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        return Boolean.parseBoolean(stringProperty(key, String.valueOf(defaultValue)));
    }

    private static LocalDateTime timeProperty(String key, String defaultValue) {
        return LocalDateTime.parse(stringProperty(key, defaultValue), FORMATTER);
    }

    private static List<String> listProperty(String key, String defaultValue) {
        String[] values = stringProperty(key, defaultValue).split(",");
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (!value.trim().isEmpty()) {
                result.add(value.trim());
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(key + " requires at least one value");
        }
        return result;
    }

    private static SendOrderMode orderModeProperty(String key, SendOrderMode defaultValue) {
        return SendOrderMode.valueOf(stringProperty(key, defaultValue.name()).trim().toUpperCase(Locale.ROOT));
    }
}
