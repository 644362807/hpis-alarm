package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hpis.common.core.constant.Constants;
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
        writeRedisSeed(options, alarms);

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
                + ", scenario=" + options.scenario
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
            AlarmKind kind = options.kindForIndex(i);
            GeneratedAlarm.Builder builder = new GeneratedAlarm.Builder()
                    .alarmId(alarmId)
                    .deviceSn(deviceSn)
                    .gatewaySn(gatewaySn)
                    .alarmTime(alarmTime)
                    .kind(kind);
            if (kind == AlarmKind.ELECTROLYTIC) {
                String irmsSn = options.electrolyticIrmsSns.get(i % options.electrolyticIrmsSns.size());
                String seq = options.electrolyticSeqs.get(i % options.electrolyticSeqs.size());
                String type = options.electrolyticTypes.get(i % options.electrolyticTypes.size());
                int subdivideIndex = (i % options.electrolyticSubdivideMod) + 1;
                int rowIndex = (i % options.electrolyticKuaMod) + 1;
                int grooveIndex = (i % options.electrolyticGrooveMod) + 1;
                builder.sceneType(options.electrolyticSceneType)
                        .alarmType(options.electrolyticAlarmType)
                        .irmsSn(irmsSn)
                        .seq(seq)
                        .type(type)
                        .subdivideIndex(subdivideIndex)
                        .kua(rowIndex)
                        .grooveIndex(grooveIndex)
                        .maxTemp(String.format(Locale.ROOT, "%.2f", options.electrolyticMaxTempBase + (i % 100) / 10.0D))
                        .sequenceId(options.electrolyticSequenceIdPrefix + "-" + seq)
                        .firstElectrodesPolarity(options.electrolyticFirstElectrodesPolarity);
            } else if (kind == AlarmKind.DISCONNECT) {
                builder.sceneType(options.generalSceneType)
                        .alarmType(options.disconnectAlarmType)
                        .irmsSn(gatewaySn);
            } else {
                builder.sceneType(options.generalSceneType)
                        .alarmType(options.generalAlarmType)
                        .irmsSn(gatewaySn);
            }
            alarms.add(builder.build());
        }
        return alarms;
    }

    private static List<GeneratedMqMessage> generateMessages(SenderOptions options, List<GeneratedAlarm> alarms) {
        Random random = new Random(options.seed * 31 + 7);
        List<GeneratedMqMessage> messages = new ArrayList<>();
        for (GeneratedAlarm alarm : alarms) {
            messages.add(new GeneratedMqMessage(alarm.alarmTime, OPER_CODE_ALARM_PUSH,
                    buildAlarmStartMessage(alarm, options), alarm.alarmId, alarm.alarmTime, alarm));
        }
        List<GeneratedAlarm> stopCandidates = new ArrayList<>(alarms);
        Collections.shuffle(stopCandidates, random);
        for (int i = 0; i < options.getExpectedStopCount(); i++) {
            GeneratedAlarm alarm = stopCandidates.get(i);
            LocalDateTime stopTime = randomStopTimeAfterAlarm(random, alarm.alarmTime,
                    options.stopStartTime, options.stopEndTime);
            messages.add(new GeneratedMqMessage(stopTime, OPER_CODE_ALARM_STOP,
                    buildAlarmStopMessage(alarm, stopTime, options), alarm.alarmId, alarm.alarmTime, alarm));
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
        rawData.put("alarmType", alarm.alarmType);
        rawData.put("cameraType", options.cameraType);
        rawData.put("deviceSn", alarm.deviceSn);
        rawData.put("gatewaySn", alarm.gatewaySn);
        rawData.put("irmsSn", alarm.irmsSn);
        rawData.put("areaSn", options.areaSn);
        rawData.put("sceneType", alarm.sceneType);
        rawData.put("time", alarm.alarmTime.format(FORMATTER));
        if (alarm.kind == AlarmKind.ELECTROLYTIC) {
            rawData.put("seq", alarm.seq);
            rawData.put("type", alarm.type);
            rawData.put("subdivideIndex", alarm.subdivideIndex);
            rawData.put("kua", alarm.kua);
            rawData.put("grooveIndex", alarm.grooveIndex);
            rawData.put("maxTemp", alarm.maxTemp);
        }
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
            if (alarm.kind == AlarmKind.ELECTROLYTIC) {
                requireGeneratedValue(alarm.alarmId, "irmsSn", alarm.irmsSn);
                requireGeneratedValue(alarm.alarmId, "seq", alarm.seq);
                requireGeneratedValue(alarm.alarmId, "type", alarm.type);
                requireGeneratedValue(alarm.alarmId, "maxTemp", alarm.maxTemp);
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

    private static void requireGeneratedValue(String alarmId, String fieldName, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("electrolytic alarm missing " + fieldName + ", alarmId=" + alarmId);
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
                row.put("kind", message.alarm.kind.name());
                row.put("scenario", options.scenario.name());
                row.put("sceneType", message.alarm.sceneType);
                row.put("alarmType", message.alarm.alarmType);
                row.put("irmsSn", message.alarm.irmsSn);
                row.put("seq", message.alarm.seq);
                row.put("sendTime", message.sendTime.format(FORMATTER));
                row.put("alarmTime", message.alarmTime.format(FORMATTER));
                writer.write(row.toJSONString());
                writer.newLine();
            }
        }
    }

    private static void writeRedisSeed(SenderOptions options, List<GeneratedAlarm> alarms) throws Exception {
        Files.createDirectories(options.outputDir);
        Path commandPath = options.outputDir.resolve("redis-seed-commands.txt");
        Path jsonlPath = options.outputDir.resolve("redis-seed.jsonl");
        try (BufferedWriter commandWriter = Files.newBufferedWriter(commandPath, StandardCharsets.UTF_8);
             BufferedWriter jsonlWriter = Files.newBufferedWriter(jsonlPath, StandardCharsets.UTF_8)) {
            Set<String> seenKeys = new HashSet<>();
            for (GeneratedAlarm alarm : alarms) {
                if (alarm.kind != AlarmKind.ELECTROLYTIC) {
                    continue;
                }
                String key = Constants.ELE_CELL_SEQUENCE_KEY + alarm.irmsSn + alarm.seq;
                if (!seenKeys.add(key)) {
                    continue;
                }
                JSONObject value = new JSONObject(new LinkedHashMap<String, Object>());
                value.put("sequenceId", alarm.sequenceId);
                value.put("firstElectrodesPolarity", alarm.firstElectrodesPolarity);
                JSONObject row = new JSONObject(new LinkedHashMap<String, Object>());
                row.put("key", key);
                row.put("value", value);
                jsonlWriter.write(row.toJSONString());
                jsonlWriter.newLine();
                commandWriter.write("SET " + redisCliQuote(key) + " " + redisCliQuote(value.toJSONString()));
                commandWriter.newLine();
            }
        }
    }

    private static String redisCliQuote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void writeSummary(SenderOptions options, long sendStartMillis, long startDoneMillis,
                                     long stopStartMillis, long sendDoneMillis, long sent) throws Exception {
        Files.createDirectories(options.outputDir);
        Properties properties = new Properties();
        properties.setProperty("runId", options.runId);
        properties.setProperty("alarmIdPrefix", options.alarmIdPrefix);
        properties.setProperty("scenario", options.scenario.name());
        properties.setProperty("alarmCount", String.valueOf(options.alarmCount));
        properties.setProperty("stopCount", String.valueOf(options.getExpectedStopCount()));
        properties.setProperty("generalCount", String.valueOf(options.countKind(AlarmKind.GENERAL)));
        properties.setProperty("electrolyticCount", String.valueOf(options.countKind(AlarmKind.ELECTROLYTIC)));
        properties.setProperty("disconnectCount", String.valueOf(options.countKind(AlarmKind.DISCONNECT)));
        properties.setProperty("generalRatio", String.valueOf(options.generalRatio));
        properties.setProperty("electrolyticRatio", String.valueOf(options.electrolyticRatio));
        properties.setProperty("disconnectRatio", String.valueOf(options.disconnectRatio));
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

    private enum AlarmScenario {
        GENERAL,
        ELECTROLYTIC,
        MIXED
    }

    private enum AlarmKind {
        GENERAL,
        ELECTROLYTIC,
        DISCONNECT
    }

    private static final class GeneratedAlarm {
        private final String alarmId;
        private final String deviceSn;
        private final String gatewaySn;
        private final LocalDateTime alarmTime;
        private final AlarmKind kind;
        private final int sceneType;
        private final int alarmType;
        private final String irmsSn;
        private final String seq;
        private final String type;
        private final int subdivideIndex;
        private final int kua;
        private final int grooveIndex;
        private final String maxTemp;
        private final String sequenceId;
        private final String firstElectrodesPolarity;

        private GeneratedAlarm(Builder builder) {
            this.alarmId = builder.alarmId;
            this.deviceSn = builder.deviceSn;
            this.gatewaySn = builder.gatewaySn;
            this.alarmTime = builder.alarmTime;
            this.kind = builder.kind;
            this.sceneType = builder.sceneType;
            this.alarmType = builder.alarmType;
            this.irmsSn = builder.irmsSn;
            this.seq = builder.seq;
            this.type = builder.type;
            this.subdivideIndex = builder.subdivideIndex;
            this.kua = builder.kua;
            this.grooveIndex = builder.grooveIndex;
            this.maxTemp = builder.maxTemp;
            this.sequenceId = builder.sequenceId;
            this.firstElectrodesPolarity = builder.firstElectrodesPolarity;
        }

        private static final class Builder {
            private String alarmId;
            private String deviceSn;
            private String gatewaySn;
            private LocalDateTime alarmTime;
            private AlarmKind kind;
            private int sceneType;
            private int alarmType;
            private String irmsSn;
            private String seq;
            private String type;
            private int subdivideIndex;
            private int kua;
            private int grooveIndex;
            private String maxTemp;
            private String sequenceId;
            private String firstElectrodesPolarity;

            private Builder alarmId(String alarmId) {
                this.alarmId = alarmId;
                return this;
            }

            private Builder deviceSn(String deviceSn) {
                this.deviceSn = deviceSn;
                return this;
            }

            private Builder gatewaySn(String gatewaySn) {
                this.gatewaySn = gatewaySn;
                return this;
            }

            private Builder alarmTime(LocalDateTime alarmTime) {
                this.alarmTime = alarmTime;
                return this;
            }

            private Builder kind(AlarmKind kind) {
                this.kind = kind;
                return this;
            }

            private Builder sceneType(int sceneType) {
                this.sceneType = sceneType;
                return this;
            }

            private Builder alarmType(int alarmType) {
                this.alarmType = alarmType;
                return this;
            }

            private Builder irmsSn(String irmsSn) {
                this.irmsSn = irmsSn;
                return this;
            }

            private Builder seq(String seq) {
                this.seq = seq;
                return this;
            }

            private Builder type(String type) {
                this.type = type;
                return this;
            }

            private Builder subdivideIndex(int subdivideIndex) {
                this.subdivideIndex = subdivideIndex;
                return this;
            }

            private Builder kua(int kua) {
                this.kua = kua;
                return this;
            }

            private Builder grooveIndex(int grooveIndex) {
                this.grooveIndex = grooveIndex;
                return this;
            }

            private Builder maxTemp(String maxTemp) {
                this.maxTemp = maxTemp;
                return this;
            }

            private Builder sequenceId(String sequenceId) {
                this.sequenceId = sequenceId;
                return this;
            }

            private Builder firstElectrodesPolarity(String firstElectrodesPolarity) {
                this.firstElectrodesPolarity = firstElectrodesPolarity;
                return this;
            }

            private GeneratedAlarm build() {
                return new GeneratedAlarm(this);
            }
        }
    }

    private static final class GeneratedMqMessage {
        private final LocalDateTime sendTime;
        private final int operCode;
        private final JSONObject payload;
        private final String alarmId;
        private final LocalDateTime alarmTime;
        private final GeneratedAlarm alarm;

        private GeneratedMqMessage(LocalDateTime sendTime, int operCode, JSONObject payload,
                                   String alarmId, LocalDateTime alarmTime, GeneratedAlarm alarm) {
            this.sendTime = sendTime;
            this.operCode = operCode;
            this.payload = payload;
            this.alarmId = alarmId;
            this.alarmTime = alarmTime;
            this.alarm = alarm;
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
        private final AlarmScenario scenario;
        private final int alarmCount;
        private final double stopRatio;
        private final double generalRatio;
        private final double electrolyticRatio;
        private final double disconnectRatio;
        private final LocalDateTime alarmStartTime;
        private final LocalDateTime alarmEndTime;
        private final LocalDateTime stopStartTime;
        private final LocalDateTime stopEndTime;
        private final List<String> deviceSns;
        private final List<String> gatewaySns;
        private final List<String> electrolyticIrmsSns;
        private final List<String> electrolyticSeqs;
        private final List<String> electrolyticTypes;
        private final int alarmDegree;
        private final int alarmType;
        private final int generalAlarmType;
        private final int electrolyticAlarmType;
        private final int disconnectAlarmType;
        private final int cameraType;
        private final int sceneType;
        private final int generalSceneType;
        private final int electrolyticSceneType;
        private final String areaSn;
        private final int electrolyticSubdivideMod;
        private final int electrolyticKuaMod;
        private final int electrolyticGrooveMod;
        private final double electrolyticMaxTempBase;
        private final String electrolyticSequenceIdPrefix;
        private final String electrolyticFirstElectrodesPolarity;
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
            this.scenario = scenarioProperty("alarm.mq.send.scenario", AlarmScenario.GENERAL);
            this.alarmCount = intProperty("alarm.mq.send.alarmCount", 100);
            this.stopRatio = doubleProperty("alarm.mq.send.stopRatio", 0.7D);
            this.generalRatio = doubleProperty("alarm.mq.send.generalRatio", 0.45D);
            this.electrolyticRatio = doubleProperty("alarm.mq.send.electrolyticRatio", 0.45D);
            this.disconnectRatio = doubleProperty("alarm.mq.send.disconnectRatio", 0.10D);
            this.alarmStartTime = timeProperty("alarm.mq.send.alarmStartTime", "2025-11-01 23:50:00");
            this.alarmEndTime = timeProperty("alarm.mq.send.alarmEndTime", "2025-11-01 23:59:50");
            this.stopStartTime = timeProperty("alarm.mq.send.stopStartTime", "2025-11-01 23:53:00");
            this.stopEndTime = timeProperty("alarm.mq.send.stopEndTime", "2025-11-01 23:59:55");
            this.deviceSns = listProperty("alarm.mq.send.deviceSns", "HM-TD2068T-5/Q20250724AACHEA4925334");
            this.gatewaySns = listProperty("alarm.mq.send.gatewaySns", "5b6bebaff7aed77ae423af7e4065ef91");
            this.electrolyticIrmsSns = listProperty("alarm.mq.send.electrolyticIrmsSns", String.join(",", this.gatewaySns));
            this.electrolyticSeqs = listProperty("alarm.mq.send.electrolyticSeqs", "SEQ-A,SEQ-B,SEQ-C");
            this.electrolyticTypes = listProperty("alarm.mq.send.electrolyticTypes", "1,2");
            this.alarmDegree = intProperty("alarm.mq.send.alarmDegree", 3);
            this.alarmType = intProperty("alarm.mq.send.alarmType", 6);
            this.generalAlarmType = intProperty("alarm.mq.send.generalAlarmType", 1);
            this.electrolyticAlarmType = intProperty("alarm.mq.send.electrolyticAlarmType", 1);
            this.disconnectAlarmType = intProperty("alarm.mq.send.disconnectAlarmType", 6);
            this.cameraType = intProperty("alarm.mq.send.cameraType", 1);
            this.sceneType = intProperty("alarm.mq.send.sceneType", 1);
            this.generalSceneType = intProperty("alarm.mq.send.generalSceneType", this.sceneType);
            this.electrolyticSceneType = intProperty("alarm.mq.send.electrolyticSceneType", 2);
            this.areaSn = stringProperty("alarm.mq.send.areaSn", "LOADTEST-AREA");
            this.electrolyticSubdivideMod = intProperty("alarm.mq.send.electrolyticSubdivideMod", 16);
            this.electrolyticKuaMod = intProperty("alarm.mq.send.electrolyticKuaMod", 2);
            this.electrolyticGrooveMod = intProperty("alarm.mq.send.electrolyticGrooveMod", 4);
            this.electrolyticMaxTempBase = doubleProperty("alarm.mq.send.electrolyticMaxTempBase", 82.0D);
            this.electrolyticSequenceIdPrefix = stringProperty("alarm.mq.send.electrolyticSequenceIdPrefix", "LOADTEST-SEQUENCE");
            this.electrolyticFirstElectrodesPolarity = stringProperty("alarm.mq.send.electrolyticFirstElectrodesPolarity", "+");
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
            if (generalRatio < 0D || electrolyticRatio < 0D || disconnectRatio < 0D) {
                throw new IllegalArgumentException("mixed ratios must be >= 0");
            }
            if (Math.abs((generalRatio + electrolyticRatio + disconnectRatio) - 1D) > 0.000001D) {
                throw new IllegalArgumentException("mixed ratios must sum to 1");
            }
            if (electrolyticSubdivideMod <= 0 || electrolyticKuaMod <= 0 || electrolyticGrooveMod <= 0) {
                throw new IllegalArgumentException("electrolytic modulo settings must be > 0");
            }
        }

        private int getExpectedStopCount() {
            return Math.min(alarmCount, (int) Math.round(alarmCount * stopRatio));
        }

        private AlarmKind kindForIndex(int index) {
            if (scenario == AlarmScenario.ELECTROLYTIC) {
                return AlarmKind.ELECTROLYTIC;
            }
            if (scenario == AlarmScenario.GENERAL) {
                return AlarmKind.GENERAL;
            }
            int bucket = index % 100;
            int generalLimit = (int) Math.round(generalRatio * 100D);
            int electrolyticLimit = generalLimit + (int) Math.round(electrolyticRatio * 100D);
            if (bucket < generalLimit) {
                return AlarmKind.GENERAL;
            }
            if (bucket < electrolyticLimit) {
                return AlarmKind.ELECTROLYTIC;
            }
            return AlarmKind.DISCONNECT;
        }

        private int countKind(AlarmKind kind) {
            int count = 0;
            for (int i = 0; i < alarmCount; i++) {
                if (kindForIndex(i) == kind) {
                    count++;
                }
            }
            return count;
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

    private static AlarmScenario scenarioProperty(String key, AlarmScenario defaultValue) {
        return AlarmScenario.valueOf(stringProperty(key, defaultValue.name()).trim().toUpperCase(Locale.ROOT));
    }
}
