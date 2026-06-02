package com.hpis.alarm.transfer;

import org.junit.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AlarmMqGraySuiteMainTest {

    @Test
    public void sharedGraySuiteRequiresRemoteStubConfirmation() {
        assertThatThrownBy(() -> GraySuiteOptions.builder().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmRemoteStub");
    }

    @Test
    public void safetySuiteRequiresFaultInjectionConfirmation() {
        assertThatThrownBy(() -> GraySuiteOptions.builder()
                .suite(GraySuiteOptions.GraySuite.SAFETY)
                .confirmRemoteStub(true)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmFaultInjection");
    }

    @Test
    public void shardingSuiteRequiresLimitConfirmation() {
        assertThatThrownBy(() -> GraySuiteOptions.builder()
                .suite(GraySuiteOptions.GraySuite.SHARDING)
                .confirmRemoteStub(true)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmShardingLimit");
    }

    @Test
    public void smokePlansThreeLowRiskRuns() {
        GraySuiteOptions options = GraySuiteOptions.builder()
                .confirmRemoteStub(true)
                .smokeAlarmCount(12)
                .build();

        List<AlarmMqGraySuiteMain.RunSpec> specs = AlarmMqGraySuiteMain.planSpecs(options);

        assertThat(specs).hasSize(3);
        assertThat(specs).extracting(AlarmMqGraySuiteMain.RunSpec::getRunName)
                .containsExactly(
                        "SMOKE-GENERAL-ALTERNATE",
                        "SMOKE-ELECTROLYTIC-ALTERNATE",
                        "SMOKE-MIXED-ALTERNATE");
        assertThat(specs).extracting(AlarmMqGraySuiteMain.RunSpec::getFaultMode)
                .containsOnly("NORMAL");
    }

    @Test
    public void safetyPlansSixExplicitFaultRuns() {
        GraySuiteOptions options = GraySuiteOptions.builder()
                .suite(GraySuiteOptions.GraySuite.SAFETY)
                .confirmRemoteStub(true)
                .confirmFaultInjection(true)
                .build();

        List<AlarmMqGraySuiteMain.RunSpec> specs = AlarmMqGraySuiteMain.planSpecs(options);

        assertThat(specs).hasSize(6);
        assertThat(specs).extracting(AlarmMqGraySuiteMain.RunSpec::getFaultMode)
                .containsExactly(
                        "DUPLICATE_START",
                        "DUPLICATE_STOP",
                        "STOP_BEFORE_START",
                        "ELECTROLYTIC_MISSING_FIELD",
                        "INVALID_STOP_MISSING_ALARM_ID",
                        "DISCONNECT_DUPLICATE");
    }
}
