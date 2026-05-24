package com.hpis.alarm.transfer;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Starts verifier sampling before sending MQ messages.
 *
 * <p>This avoids the old measurement gap where sender finished first and the
 * verifier was started later, causing small runs to look slower than large runs.</p>
 */
public class AlarmMqLoadOrchestratorMain {

    public static void main(String[] args) throws Exception {
        normalizeProperties();
        long verifierLeadMillis = longProperty("alarm.loadtest.orchestrator.verifierLeadMillis", 500L);
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alarm-load-verifier");
            thread.setDaemon(true);
            return thread;
        });
        Future<AlarmMqLoadVerifierMain.VerifyResult> verifier = executor.submit(new Callable<AlarmMqLoadVerifierMain.VerifyResult>() {
            @Override
            public AlarmMqLoadVerifierMain.VerifyResult call() throws Exception {
                return AlarmMqLoadVerifierMain.run();
            }
        });
        try {
            Thread.sleep(Math.max(0L, verifierLeadMillis));
            AlarmMqDirectSenderMain.main(args);
            AlarmMqLoadVerifierMain.VerifyResult result = verifier.get();
            if (!result.isSuccess()) {
                System.err.println("orchestrated loadtest failed, report=" + result.getReport());
                System.exit(2);
            }
            System.out.println("orchestrated loadtest passed, report=" + result.getReport());
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void normalizeProperties() {
        String runId = firstProperty("alarm.loadtest.runId", "alarm.mq.send.runId");
        if (runId == null) {
            runId = "AUTO-" + System.currentTimeMillis();
        }
        setIfAbsent("alarm.loadtest.runId", runId);
        setIfAbsent("alarm.mq.send.runId", runId);

        String prefix = firstProperty("alarm.loadtest.alarmIdPrefix", "alarm.mq.send.alarmIdPrefix");
        if (prefix == null) {
            prefix = compactAlarmIdPrefix(runId);
        }
        setIfAbsent("alarm.loadtest.alarmIdPrefix", prefix);
        setIfAbsent("alarm.mq.send.alarmIdPrefix", prefix);

        String outputDir = firstProperty("alarm.loadtest.outputDir", "alarm.mq.send.outputDir");
        if (outputDir == null) {
            outputDir = "target/alarm-loadtest/" + runId;
        }
        setIfAbsent("alarm.loadtest.outputDir", outputDir);
        setIfAbsent("alarm.mq.send.outputDir", outputDir);

        String alarmCount = firstProperty("alarm.loadtest.alarmCount", "alarm.mq.send.alarmCount");
        if (alarmCount == null) {
            alarmCount = "100";
        }
        setIfAbsent("alarm.loadtest.alarmCount", alarmCount);
        setIfAbsent("alarm.mq.send.alarmCount", alarmCount);

        String stopCount = System.getProperty("alarm.loadtest.stopCount");
        if (stopCount == null || stopCount.trim().isEmpty()) {
            int count = Integer.parseInt(alarmCount);
            double stopRatio = doubleProperty("alarm.mq.send.stopRatio", 0.7D);
            stopCount = String.valueOf(Math.min(count, (int) Math.round(count * stopRatio)));
            System.setProperty("alarm.loadtest.stopCount", stopCount);
        }

        if (System.getProperty("alarm.loadtest.expectedElectrolyticCount") == null) {
            System.setProperty("alarm.loadtest.expectedElectrolyticCount", expectedElectrolyticCount(alarmCount));
        }
    }

    private static String expectedElectrolyticCount(String alarmCountText) {
        int alarmCount = Integer.parseInt(alarmCountText);
        String scenario = System.getProperty("alarm.mq.send.scenario", "GENERAL").trim().toUpperCase(Locale.ROOT);
        if ("ELECTROLYTIC".equals(scenario)) {
            return String.valueOf(alarmCount);
        }
        if ("MIXED".equals(scenario)) {
            double ratio = doubleProperty("alarm.mq.send.electrolyticRatio", 0.45D);
            return String.valueOf(Math.min(alarmCount, (int) Math.round(alarmCount * ratio)));
        }
        return "0";
    }

    private static String compactAlarmIdPrefix(String runId) {
        String compact = runId == null ? "" : runId.replaceAll("[^A-Za-z0-9]", "");
        if (compact.length() > 24) {
            compact = compact.substring(compact.length() - 24);
        }
        return compact.isEmpty() ? "AUTO" + (System.currentTimeMillis() % 1_000_000_000L) : compact;
    }

    private static String firstProperty(String firstKey, String secondKey) {
        String value = System.getProperty(firstKey);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        value = System.getProperty(secondKey);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null || System.getProperty(key).trim().isEmpty()) {
            System.setProperty(key, value);
        }
    }

    private static long longProperty(String key, long defaultValue) {
        String value = System.getProperty(key);
        return value == null || value.trim().isEmpty() ? defaultValue : Long.parseLong(value.trim());
    }

    private static double doubleProperty(String key, double defaultValue) {
        String value = System.getProperty(key);
        return value == null || value.trim().isEmpty() ? defaultValue : Double.parseDouble(value.trim());
    }
}
