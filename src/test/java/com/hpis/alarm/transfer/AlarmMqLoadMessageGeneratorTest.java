package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Test;

import java.io.IOException;
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
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成报警 MQ 压测消息。
 *
 * <p>这个测试不连接 RabbitMQ，只生成 RabbitMQAlarmListener 能直接解析的 JSONL 文件。
 * 每一行是一条完整 MQ 消息，包含 dataSync/cmdData/rawData 结构；开始报警使用 operCode=259，
 * 停止报警使用 operCode=260。这样可以先离线检查报警数量、停止比例、alarmId 唯一性，
 * 再把生成文件交给压测脚本逐行发送到队列。</p>
 *
 * <p>可通过 Maven -D 参数控制生成规模，例如：</p>
 *
 * <pre>
 * mvn -pl hpis-alarm -Dtest=AlarmMqLoadMessageGeneratorTest test \
 *   -Dalarm.mq.test.alarmCount=10000 \
 *   -Dalarm.mq.test.stopRatio=0.7 \
 *   -Dalarm.mq.test.alarmStartTime="2025-10-10 09:00:00" \
 *   -Dalarm.mq.test.alarmEndTime="2025-10-10 10:00:00" \
 *   -Dalarm.mq.test.stopStartTime="2025-10-10 09:30:00" \
 *   -Dalarm.mq.test.stopEndTime="2025-10-10 11:00:00" \
 *   -Dalarm.mq.test.deviceSns="DEVICE-A,DEVICE-B"
 * </pre>
 */
public class AlarmMqLoadMessageGeneratorTest {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int OPER_CODE_ALARM_PUSH = 259;

    private static final int OPER_CODE_ALARM_STOP = 260;

    @Test
    public void generateAlarmStartAndStopMqMessages() throws IOException {
        GeneratorOptions options = GeneratorOptions.fromSystemProperties();
        List<GeneratedAlarm> alarms = generateAlarms(options);
        List<GeneratedMqMessage> messages = generateMessages(options, alarms);

        writeJsonLines(options.getOutputPath(), messages);

        long startCount = messages.stream()
                .filter(message -> message.getOperCode() == OPER_CODE_ALARM_PUSH)
                .count();
        long stopCount = messages.stream()
                .filter(message -> message.getOperCode() == OPER_CODE_ALARM_STOP)
                .count();
        Set<String> alarmIds = new HashSet<>();
        for (GeneratedAlarm alarm : alarms) {
            alarmIds.add(alarm.getAlarmId());
            assertThat(options.getDeviceSns()).contains(alarm.getDeviceSn());
        }

        assertThat(startCount).isEqualTo(options.getAlarmCount());
        assertThat(stopCount).isEqualTo(options.getExpectedStopCount());
        assertThat(alarmIds).hasSize(options.getAlarmCount());
        assertThat(Files.exists(options.getOutputPath())).isTrue();
    }

    private List<GeneratedAlarm> generateAlarms(GeneratorOptions options) {
        Random random = new Random(options.getSeed());
        List<GeneratedAlarm> alarms = new ArrayList<>();
        for (int i = 0; i < options.getAlarmCount(); i++) {
            LocalDateTime alarmTime = randomTime(random, options.getAlarmStartTime(), options.getAlarmEndTime());
            String deviceSn = options.getDeviceSns().get(random.nextInt(options.getDeviceSns().size()));
            String gatewaySn = options.getGatewaySns().get(random.nextInt(options.getGatewaySns().size()));
            String alarmId = "{" + UUID.nameUUIDFromBytes((options.getSeed() + "-" + i + "-" + deviceSn)
                    .getBytes(StandardCharsets.UTF_8)).toString() + "}";
            alarms.add(new GeneratedAlarm(alarmId, deviceSn, gatewaySn, alarmTime));
        }
        return alarms;
    }

    private List<GeneratedMqMessage> generateMessages(GeneratorOptions options, List<GeneratedAlarm> alarms) {
        Random random = new Random(options.getSeed() * 31 + 7);
        List<GeneratedMqMessage> messages = new ArrayList<>();
        for (GeneratedAlarm alarm : alarms) {
            messages.add(new GeneratedMqMessage(alarm.getAlarmTime(), OPER_CODE_ALARM_PUSH,
                    buildAlarmStartMessage(alarm, options)));
        }

        List<GeneratedAlarm> stopCandidates = new ArrayList<>(alarms);
        Collections.shuffle(stopCandidates, random);
        for (int i = 0; i < options.getExpectedStopCount(); i++) {
            GeneratedAlarm alarm = stopCandidates.get(i);
            LocalDateTime stopTime = randomStopTimeAfterAlarm(random, alarm.getAlarmTime(),
                    options.getStopStartTime(), options.getStopEndTime());
            messages.add(new GeneratedMqMessage(stopTime, OPER_CODE_ALARM_STOP,
                    buildAlarmStopMessage(alarm, stopTime, options)));
        }

        messages.sort(Comparator.comparing(GeneratedMqMessage::getSendTime)
                .thenComparing(GeneratedMqMessage::getOperCode));
        return messages;
    }

    private JSONObject buildAlarmStartMessage(GeneratedAlarm alarm, GeneratorOptions options) {
        JSONObject rawData = new JSONObject(new LinkedHashMap<String, Object>());
        rawData.put("alarmDegree", options.getAlarmDegree());
        rawData.put("alarmId", alarm.getAlarmId());
        rawData.put("alarmType", options.getAlarmType());
        rawData.put("cameraType", options.getCameraType());
        rawData.put("deviceSn", alarm.getDeviceSn());
        rawData.put("gatewaySn", alarm.getGatewaySn());
        rawData.put("sceneType", options.getSceneType());
        rawData.put("time", alarm.getAlarmTime().format(FORMATTER));
        return buildEnvelope(alarm.getGatewaySn(), OPER_CODE_ALARM_PUSH, rawData, options);
    }

    private JSONObject buildAlarmStopMessage(GeneratedAlarm alarm, LocalDateTime stopTime, GeneratorOptions options) {
        JSONObject rawData = new JSONObject(new LinkedHashMap<String, Object>());
        rawData.put("alarmId", alarm.getAlarmId());
        rawData.put("time", stopTime.format(FORMATTER));
        return buildEnvelope(alarm.getGatewaySn(), OPER_CODE_ALARM_STOP, rawData, options);
    }

    private JSONObject buildEnvelope(String gatewaySn, int operCode, JSONObject rawData, GeneratorOptions options) {
        JSONObject cmdData = new JSONObject(new LinkedHashMap<String, Object>());
        cmdData.put("confItems", options.getConfItems());
        cmdData.put("deviceSn", gatewaySn);
        cmdData.put("operCode", operCode);
        cmdData.put("rawData", rawData);
        cmdData.put("version", 1);

        JSONObject envelope = new JSONObject(new LinkedHashMap<String, Object>());
        envelope.put("cmd", "dataSync");
        envelope.put("cmdData", cmdData);
        envelope.put("cmdSeq", options.getCmdSeq());
        envelope.put("servId", "hp_access");
        envelope.put("times", options.getTimes());
        return envelope;
    }

    private LocalDateTime randomStopTimeAfterAlarm(Random random, LocalDateTime alarmTime,
                                                  LocalDateTime stopStartTime, LocalDateTime stopEndTime) {
        LocalDateTime lowerBound = alarmTime.isAfter(stopStartTime) ? alarmTime.plusSeconds(1) : stopStartTime;
        if (lowerBound.isAfter(stopEndTime)) {
            return alarmTime.plusMinutes(1);
        }
        return randomTime(random, lowerBound, stopEndTime);
    }

    private LocalDateTime randomTime(Random random, LocalDateTime startTime, LocalDateTime endTime) {
        long seconds = Math.max(0, Duration.between(startTime, endTime).getSeconds());
        if (seconds == 0) {
            return startTime;
        }
        return startTime.plusSeconds(nextLong(random, seconds + 1));
    }

    private long nextLong(Random random, long bound) {
        long bits;
        long value;
        do {
            bits = random.nextLong() & Long.MAX_VALUE;
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0L);
        return value;
    }

    private void writeJsonLines(Path outputPath, List<GeneratedMqMessage> messages) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> lines = new ArrayList<>();
        for (GeneratedMqMessage message : messages) {
            lines.add(JSON.toJSONString(message.getPayload()));
        }
        Files.write(outputPath, lines, StandardCharsets.UTF_8);
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

        private String getAlarmId() {
            return alarmId;
        }

        private String getDeviceSn() {
            return deviceSn;
        }

        private String getGatewaySn() {
            return gatewaySn;
        }

        private LocalDateTime getAlarmTime() {
            return alarmTime;
        }
    }

    private static final class GeneratedMqMessage {
        private final LocalDateTime sendTime;
        private final int operCode;
        private final JSONObject payload;

        private GeneratedMqMessage(LocalDateTime sendTime, int operCode, JSONObject payload) {
            this.sendTime = sendTime;
            this.operCode = operCode;
            this.payload = payload;
        }

        private LocalDateTime getSendTime() {
            return sendTime;
        }

        private int getOperCode() {
            return operCode;
        }

        private JSONObject getPayload() {
            return payload;
        }
    }

    private static final class GeneratorOptions {
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
        private final long times;
        private final long seed;
        private final Path outputPath;

        private GeneratorOptions(Map<String, String> props) {
            this.alarmCount = intProp(props, "alarm.mq.test.alarmCount", 1000);
            this.stopRatio = doubleProp(props, "alarm.mq.test.stopRatio", 0.7D);
            this.alarmStartTime = timeProp(props, "alarm.mq.test.alarmStartTime", "2025-10-10 09:00:00");
            this.alarmEndTime = timeProp(props, "alarm.mq.test.alarmEndTime", "2025-10-10 10:00:00");
            this.stopStartTime = timeProp(props, "alarm.mq.test.stopStartTime", "2025-10-10 09:30:00");
            this.stopEndTime = timeProp(props, "alarm.mq.test.stopEndTime", "2025-10-10 11:00:00");
            this.deviceSns = listProp(props, "alarm.mq.test.deviceSns",
                    "HM-TD2068T-5/Q20250724AACHEA4925334");
            this.gatewaySns = listProp(props, "alarm.mq.test.gatewaySns",
                    "5b6bebaff7aed77ae423af7e4065ef91");
            this.alarmDegree = intProp(props, "alarm.mq.test.alarmDegree", 3);
            this.alarmType = intProp(props, "alarm.mq.test.alarmType", 6);
            this.cameraType = intProp(props, "alarm.mq.test.cameraType", 1);
            this.sceneType = intProp(props, "alarm.mq.test.sceneType", 1);
            this.confItems = intProp(props, "alarm.mq.test.confItems", 1000);
            this.cmdSeq = intProp(props, "alarm.mq.test.cmdSeq", 3);
            this.times = longProp(props, "alarm.mq.test.times", 813376452L);
            this.seed = longProp(props, "alarm.mq.test.seed", 20260509L);
            this.outputPath = Paths.get(props.getOrDefault("alarm.mq.test.output",
                    "target/generated-test-data/alarm-mq-load-messages.jsonl"));

            assertThat(alarmCount).isGreaterThan(0);
            assertThat(stopRatio).isBetween(0D, 1D);
            assertThat(alarmEndTime).isAfterOrEqualTo(alarmStartTime);
            assertThat(stopEndTime).isAfterOrEqualTo(stopStartTime);
            assertThat(deviceSns).isNotEmpty();
            assertThat(gatewaySns).isNotEmpty();
        }

        private static GeneratorOptions fromSystemProperties() {
            Map<String, String> props = new LinkedHashMap<>();
            for (String propertyName : System.getProperties().stringPropertyNames()) {
                props.put(propertyName, System.getProperty(propertyName));
            }
            return new GeneratorOptions(props);
        }

        private int getExpectedStopCount() {
            return (int) Math.round(alarmCount * stopRatio);
        }

        private int getAlarmCount() {
            return alarmCount;
        }

        private LocalDateTime getAlarmStartTime() {
            return alarmStartTime;
        }

        private LocalDateTime getAlarmEndTime() {
            return alarmEndTime;
        }

        private LocalDateTime getStopStartTime() {
            return stopStartTime;
        }

        private LocalDateTime getStopEndTime() {
            return stopEndTime;
        }

        private List<String> getDeviceSns() {
            return deviceSns;
        }

        private List<String> getGatewaySns() {
            return gatewaySns;
        }

        private int getAlarmDegree() {
            return alarmDegree;
        }

        private int getAlarmType() {
            return alarmType;
        }

        private int getCameraType() {
            return cameraType;
        }

        private int getSceneType() {
            return sceneType;
        }

        private int getConfItems() {
            return confItems;
        }

        private int getCmdSeq() {
            return cmdSeq;
        }

        private long getTimes() {
            return times;
        }

        private long getSeed() {
            return seed;
        }

        private Path getOutputPath() {
            return outputPath;
        }

        private static int intProp(Map<String, String> props, String key, int defaultValue) {
            return Integer.parseInt(props.getOrDefault(key, String.valueOf(defaultValue)));
        }

        private static long longProp(Map<String, String> props, String key, long defaultValue) {
            return Long.parseLong(props.getOrDefault(key, String.valueOf(defaultValue)));
        }

        private static double doubleProp(Map<String, String> props, String key, double defaultValue) {
            return Double.parseDouble(props.getOrDefault(key, String.valueOf(defaultValue)));
        }

        private static LocalDateTime timeProp(Map<String, String> props, String key, String defaultValue) {
            return LocalDateTime.parse(props.getOrDefault(key, defaultValue), FORMATTER);
        }

        private static List<String> listProp(Map<String, String> props, String key, String defaultValue) {
            String value = props.getOrDefault(key, defaultValue);
            String[] parts = value.split(",");
            List<String> result = new ArrayList<>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }
    }
}
