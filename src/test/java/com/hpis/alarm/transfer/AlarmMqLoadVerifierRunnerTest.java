package com.hpis.alarm.transfer;

import org.junit.Assume;
import org.junit.Test;

/**
 * Surefire runner for the standalone MQ load verifier.
 */
public class AlarmMqLoadVerifierRunnerTest {

    @Test
    public void runVerifierWhenExplicitlyEnabled() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("alarm.loadtest.runner.enabled"));
        AlarmMqLoadVerifierMain.main(new String[0]);
    }
}
