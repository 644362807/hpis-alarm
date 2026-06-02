package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

public class AlarmMqDirectSenderMainTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void safetyFaultModesAreGeneratedOfflineWithoutMq() throws Exception {
        assertManifest("GENERAL", "DUPLICATE_START", 41, 259);
        assertManifest("GENERAL", "DUPLICATE_STOP", 41, 259);
        assertManifest("GENERAL", "STOP_BEFORE_START", 40, 260);
        assertManifest("ELECTROLYTIC", "ELECTROLYTIC_MISSING_FIELD", 41, 259);
        assertManifest("GENERAL", "INVALID_STOP_MISSING_ALARM_ID", 41, 259);
        assertManifest("MIXED", "DISCONNECT_DUPLICATE", 201, 259);
    }

    private void assertManifest(String scenario, String faultMode, int expectedLines, int expectedFirstOperCode)
            throws Exception {
        Path output = temporaryFolder.newFolder(faultMode).toPath();
        try (SystemPropertiesScope scope = new SystemPropertiesScope()) {
            scope.set("alarm.mq.send.outputDir", output.toString());
            scope.set("alarm.mq.send.runId", "DRY-" + faultMode);
            scope.set("alarm.mq.send.alarmCount", "DISCONNECT_DUPLICATE".equals(faultMode) ? "100" : "20");
            scope.set("alarm.mq.send.stopRatio", "1.0");
            scope.set("alarm.mq.send.scenario", scenario);
            scope.set("alarm.mq.send.orderMode", "ALTERNATE");
            scope.set("alarm.mq.send.faultMode", faultMode);
            scope.set("alarm.mq.send.dryRun", "true");
            scope.set("alarm.mq.send.previewCount", "0");
            AlarmMqDirectSenderMain.main(new String[0]);
        }

        List<String> lines = Files.readAllLines(output.resolve("manifest.jsonl"), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(expectedLines);
        JSONObject first = JSON.parseObject(lines.get(0));
        assertThat(first.getIntValue("operCode")).isEqualTo(expectedFirstOperCode);
    }

    private static final class SystemPropertiesScope implements AutoCloseable {
        private final Map<String, String> original = new LinkedHashMap<>();

        private void set(String key, String value) {
            original.put(key, System.getProperty(key));
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
