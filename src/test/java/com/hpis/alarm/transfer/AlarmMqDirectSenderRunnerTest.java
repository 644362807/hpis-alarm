package com.hpis.alarm.transfer;

import org.junit.Assume;
import org.junit.Test;

/**
 * Surefire runner for the standalone MQ sender.
 *
 * <p>This test is disabled unless alarm.loadtest.runner.enabled=true is passed.
 * The PowerShell load-test script uses it to run with the full Maven reactor
 * classpath, avoiding local repository resolution problems for hpis modules.</p>
 */
public class AlarmMqDirectSenderRunnerTest {

    @Test
    public void runSenderWhenExplicitlyEnabled() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("alarm.loadtest.runner.enabled"));
        AlarmMqDirectSenderMain.main(new String[0]);
    }
}
