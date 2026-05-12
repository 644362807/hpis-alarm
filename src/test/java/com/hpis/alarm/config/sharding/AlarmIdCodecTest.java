package com.hpis.alarm.config.sharding;

import org.junit.Test;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AlarmIdCodecTest {

    @Test
    public void nextIdCanDecodeDateSliceWorkerSeedAndRowNo() throws Exception {
        AlarmShardProperties properties = new AlarmShardProperties();
        properties.getId().setWorkerId(128);
        AlarmIdCodec codec = new AlarmIdCodec(properties);
        Date alarmTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-04-15 10:20:30");

        long alarmId = codec.nextId(alarmTime, 2, 1234L, 0x2A5L);

        AlarmIdCodec.DecodedAlarmId decoded = codec.decode(alarmId);
        assertThat(decoded).isNotNull();
        assertThat(decoded.getAlarmDate()).isEqualTo(LocalDate.of(2026, 4, 15));
        assertThat(decoded.getDayOffset()).isEqualTo(ChronoUnit.DAYS.between(
                LocalDate.of(2020, 1, 1), LocalDate.of(2026, 4, 15)));
        assertThat(decoded.getMonthKey()).isEqualTo("202604");
        assertThat(decoded.getSliceNo()).isEqualTo(2);
        assertThat(decoded.getTableSuffix()).isEqualTo("202604_02");
        assertThat(decoded.getWorkerId()).isEqualTo(128);
        assertThat(decoded.getSnowflakeSeed()).isEqualTo(0xA5);
        assertThat(decoded.getRowNo()).isEqualTo(1234L);
    }

    @Test
    public void idsWithDifferentRowNoAreDifferent() throws Exception {
        AlarmShardProperties properties = new AlarmShardProperties();
        AlarmIdCodec codec = new AlarmIdCodec(properties);
        Date alarmTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-04-15 10:20:30");

        long firstId = codec.nextId(alarmTime, 0, 1L, 11L);
        long secondId = codec.nextId(alarmTime, 0, 2L, 11L);

        assertThat(firstId).isNotEqualTo(secondId);
    }

    @Test
    public void nextIdRejectsOutOfRangeValues() throws Exception {
        AlarmShardProperties properties = new AlarmShardProperties();
        AlarmIdCodec codec = new AlarmIdCodec(properties);
        Date alarmTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse("2026-04-15 10:20:30");

        assertThatThrownBy(() -> codec.nextId(alarmTime, 256, 1L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.nextId(alarmTime, 0, AlarmIdCodec.MAX_ROW_NO + 1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void constructorRejectsInvalidWorkerIdAndSliceCapacity() {
        AlarmShardProperties invalidWorker = new AlarmShardProperties();
        invalidWorker.getId().setWorkerId(256);
        assertThatThrownBy(() -> new AlarmIdCodec(invalidWorker))
                .isInstanceOf(IllegalArgumentException.class);

        AlarmShardProperties invalidCapacity = new AlarmShardProperties();
        invalidCapacity.setMaxRowsPerSlice(AlarmIdCodec.MAX_ROW_NO + 2);
        assertThatThrownBy(() -> new AlarmIdCodec(invalidCapacity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void decodeRejectsNullOrNonPositiveIds() {
        AlarmShardProperties properties = new AlarmShardProperties();
        AlarmIdCodec codec = new AlarmIdCodec(properties);

        assertThat(codec.decode(null)).isNull();
        assertThat(codec.decode(0L)).isNull();
        assertThat(codec.decode(-1L)).isNull();
    }
}
