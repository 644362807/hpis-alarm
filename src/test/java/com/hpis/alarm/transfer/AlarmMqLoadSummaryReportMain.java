package com.hpis.alarm.transfer;

import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Aggregates per-run load-test reports into one Markdown report.
 */
public class AlarmMqLoadSummaryReportMain {

    public static void main(String[] args) throws Exception {
        SummaryOptions options = SummaryOptions.fromSystemProperties();
        List<RunReport> reports = loadReports(options.baseDir);
        if (options.outputPath.getParent() != null) {
            Files.createDirectories(options.outputPath.getParent());
        }
        writeSummary(options, reports);
        System.out.println("summary report written: " + options.outputPath);
    }

    private static List<RunReport> loadReports(Path baseDir) throws Exception {
        List<RunReport> reports = new ArrayList<>();
        if (!Files.exists(baseDir)) {
            return reports;
        }
        try (java.util.stream.Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(path -> {
                        try {
                            Path reportPath = path.resolve("report.md");
                            if (Files.exists(reportPath)) {
                                reports.add(loadReport(path));
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException(ex);
                        }
                    });
        }
        return reports;
    }

    private static RunReport loadReport(Path runDir) throws Exception {
        Properties send = new Properties();
        Path sendSummary = runDir.resolve("send-summary.properties");
        if (Files.exists(sendSummary)) {
            try (BufferedReader reader = Files.newBufferedReader(sendSummary, StandardCharsets.UTF_8)) {
                send.load(reader);
            }
        }
        RunReport report = new RunReport();
        report.runId = send.getProperty("runId", runDir.getFileName().toString());
        report.scenario = send.getProperty("scenario", "unknown");
        report.orderMode = send.getProperty("orderMode", "unknown");
        report.alarmCount = send.getProperty("alarmCount", "unknown");
        report.stopCount = send.getProperty("stopCount", "unknown");
        report.electrolyticCount = send.getProperty("electrolyticCount", "0");
        report.sentMessages = send.getProperty("sentMessages", "unknown");
        report.sendElapsedMillis = send.getProperty("sendElapsedMillis", "unknown");

        List<String> lines = Files.readAllLines(runDir.resolve("report.md"), StandardCharsets.UTF_8);
        report.result = markdownValue(lines, "- Result:", "unknown");
        report.verifyElapsedMillis = markdownValue(lines, "- Verify elapsed ms:", "unknown");
        report.consumeElapsedMillis = markdownValue(lines, "- Consume elapsed after send ms:", "unknown");
        report.closedLoopElapsedMillis = markdownValue(lines, "- Closed-loop elapsed ms:", "unknown");
        report.verifierStartupGapMillis = markdownValue(lines, "- Verifier startup gap ms:", "unknown");
        report.queueDrainElapsedMillis = markdownValue(lines, "- Queue drain elapsed after send ms:", "unknown");
        report.dbClosedElapsedMillis = markdownValue(lines, "- DB closed elapsed after send ms:", "unknown");
        report.trueClosedLoopElapsedMillis = markdownValue(lines, "- True closed-loop elapsed ms:", "unknown");
        report.sendThroughput = markdownValue(lines, "- Send throughput msg/s:", "unknown");
        report.closedLoopThroughput = markdownValue(lines, "- Closed-loop alarm throughput rows/s:", "unknown");
        report.trueClosedLoopThroughput = markdownValue(lines, "- True closed-loop alarm throughput rows/s:", "unknown");
        report.queueReady = markdownValue(lines, "- Queue ready:", "unknown");
        report.alarmRows = markdownValue(lines, "- Alarm rows:", "unknown");
        report.closedRows = markdownValue(lines, "- Closed rows:", "unknown");
        report.electrolyticRows = markdownValue(lines, "- Electrolytic rows joined by alarm_id:", "unknown");
        report.electrolyticMissingRows = markdownValue(lines, "- Electrolytic rows missing for run scene_type=2:", "unknown");
        report.electrolyticNullRows = markdownValue(lines, "- Electrolytic rows with null alarm_id observed in physical EC tables:", "unknown");
        return report;
    }

    private static String markdownValue(List<String> lines, String prefix, String defaultValue) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                int first = line.indexOf('`');
                int last = line.lastIndexOf('`');
                if (first >= 0 && last > first) {
                    return line.substring(first + 1, last);
                }
                return line.substring(prefix.length()).trim();
            }
        }
        return defaultValue;
    }

    private static void writeSummary(SummaryOptions options, List<RunReport> reports) throws Exception {
        boolean allPass = !reports.isEmpty();
        for (RunReport report : reports) {
            allPass = allPass && "PASS".equals(report.result);
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                Files.newOutputStream(options.outputPath), StandardCharsets.UTF_8)) {
            writer.write("# 报警 MQ-MySQL 批量消费压测报告\n\n");
            writer.write("- 生成日期: `" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "`\n");
            writer.write("- Run 数量: `" + reports.size() + "`\n");
            writer.write("- 总结论: `" + (allPass ? "PASS" : "待补齐或存在失败 run") + "`\n\n");

            writer.write("## 汇总表\n\n");
            writer.write("| Run ID | 场景 | 顺序 | 数据量 | stop | EC | 结果 | 发送ms | verifier空窗ms | 队列清空ms | DB闭合ms | 可信闭环ms | 可信rows/s | alarmRows | closedRows | queueReady | EC rows | EC missing | EC null |\n");
            writer.write("|---|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
            for (RunReport report : reports) {
                writer.write("| `" + report.runId + "` | `" + report.scenario + "` | `" + report.orderMode + "` | "
                        + report.alarmCount + " | " + report.stopCount + " | " + report.electrolyticCount + " | `"
                        + report.result + "` | " + report.sendElapsedMillis + " | " + report.verifierStartupGapMillis
                        + " | " + report.queueDrainElapsedMillis + " | " + report.dbClosedElapsedMillis
                        + " | " + report.trueClosedLoopElapsedMillis + " | " + report.trueClosedLoopThroughput
                        + " | " + report.alarmRows + " | " + report.closedRows + " | " + report.queueReady
                        + " | " + report.electrolyticRows + " | " + report.electrolyticMissingRows
                        + " | " + report.electrolyticNullRows + " |\n");
            }

            writer.write("\n## 结论检查\n\n");
            writer.write("- consumer batch 普通/电解槽/混合场景是否通过: `" + scenarioConclusion(reports) + "`\n");
            writer.write("- 2000/10000/50000/100000 消费时间与吞吐: 见汇总表 `可信闭环ms` 与 `可信rows/s`；`-1` 表示 verifier 启动过晚，该轮不采纳性能结论。\n");
            writer.write("- ID 不一致风险: `" + riskText(reports, "electrolyticMissingRows") + "`\n");
            writer.write("- 缺参毒消息风险: 由单测覆盖 DROP + ack；真实毒消息验证需单独保留 run 记录。\n");
            writer.write("- MQ 重投风险: 以各 run `queueReady=0` 且无持续 nack/requeue 日志为准。\n");
            writer.write("- MySQL 半成品风险: 以 `alarmRows/closedRows/stop APPLIED/EC missing` 验收项为准。\n");
        }
    }

    private static String scenarioConclusion(List<RunReport> reports) {
        boolean hasGeneral = false;
        boolean hasEc = false;
        boolean hasMixed = false;
        boolean pass = true;
        for (RunReport report : reports) {
            hasGeneral = hasGeneral || "GENERAL".equals(report.scenario);
            hasEc = hasEc || "ELECTROLYTIC".equals(report.scenario);
            hasMixed = hasMixed || "MIXED".equals(report.scenario);
            pass = pass && "PASS".equals(report.result);
        }
        if (pass && hasGeneral && hasEc && hasMixed) {
            return "PASS";
        }
        return "待补齐或存在失败 run";
    }

    private static String riskText(List<RunReport> reports, String fieldName) {
        for (RunReport report : reports) {
            if ("electrolyticMissingRows".equals(fieldName) && !"0".equals(report.electrolyticMissingRows)
                    && !"unknown".equals(report.electrolyticMissingRows)) {
                return "存在风险";
            }
        }
        return "未发现";
    }

    private static final class RunReport {
        private String runId;
        private String scenario;
        private String orderMode;
        private String alarmCount;
        private String stopCount;
        private String electrolyticCount;
        private String sentMessages;
        private String sendElapsedMillis;
        private String result;
        private String verifyElapsedMillis;
        private String consumeElapsedMillis;
        private String closedLoopElapsedMillis;
        private String verifierStartupGapMillis;
        private String queueDrainElapsedMillis;
        private String dbClosedElapsedMillis;
        private String trueClosedLoopElapsedMillis;
        private String sendThroughput;
        private String closedLoopThroughput;
        private String trueClosedLoopThroughput;
        private String queueReady;
        private String alarmRows;
        private String closedRows;
        private String electrolyticRows;
        private String electrolyticMissingRows;
        private String electrolyticNullRows;
    }

    private static final class SummaryOptions {
        private final Path baseDir;
        private final Path outputPath;

        private SummaryOptions() {
            this.baseDir = Paths.get(stringProperty("alarm.loadtest.summary.baseDir", "target/alarm-loadtest"));
            this.outputPath = Paths.get(stringProperty("alarm.loadtest.summary.output",
                    "doc/报警MQ-MySQL批量消费压测报告-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".md"));
        }

        private static SummaryOptions fromSystemProperties() {
            return new SummaryOptions();
        }
    }

    private static String stringProperty(String key, String defaultValue) {
        String value = System.getProperty(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }
}
