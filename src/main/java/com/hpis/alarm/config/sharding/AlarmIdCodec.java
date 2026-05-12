package com.hpis.alarm.config.sharding;

import lombok.Data;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 报警内部 ID 编解码器。
 *
 * <p>外部设备上报的 alarmId 继续保存到 alarm_cid。内部 alarm_id 需要同时满足三个目标：
 * 一是保持 bigint Long，不改现有接口和数据库字段；二是能反推出业务日期和 slice，支持按 ID
 * 直达 alarm_yyyyMM_nn；三是同一批历史时间压测复跑、服务重启或多实例部署时不复用旧主键。</p>
 *
 * <p>v2 采用 63 位正数布局：
 * dayOffset(15) + sliceNo(8) + workerId(8) + snowflakeSeed(9) + rowNo(23)。
 * 唯一性主要由数据库 Segment 预占得到的 rowNo 保证，snowflakeSeed 只作为扰动字段保留，
 * 不承担跨重启唯一性的核心职责。</p>
 */
public class AlarmIdCodec {

    private static final LocalDate EPOCH_DATE = LocalDate.of(2020, 1, 1);

    private static final int DAY_BITS = 15;

    private static final int SLICE_BITS = 8;

    private static final int WORKER_BITS = 8;

    private static final int SNOWFLAKE_SEED_BITS = 9;

    private static final int ROW_BITS = 23;

    private static final int ROW_SHIFT = 0;

    private static final int SNOWFLAKE_SEED_SHIFT = ROW_SHIFT + ROW_BITS;

    private static final int WORKER_SHIFT = SNOWFLAKE_SEED_SHIFT + SNOWFLAKE_SEED_BITS;

    private static final int SLICE_SHIFT = WORKER_SHIFT + WORKER_BITS;

    private static final int DAY_SHIFT = SLICE_SHIFT + SLICE_BITS;

    private static final long MAX_DAY_OFFSET = (1L << DAY_BITS) - 1;

    private static final int MAX_SLICE_NO = (1 << SLICE_BITS) - 1;

    private static final int MAX_WORKER_ID = (1 << WORKER_BITS) - 1;

    private static final int MAX_SNOWFLAKE_SEED = (1 << SNOWFLAKE_SEED_BITS) - 1;

    public static final long MAX_ROW_NO = (1L << ROW_BITS) - 1;

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private final AlarmShardProperties properties;

    public AlarmIdCodec(AlarmShardProperties properties) {
        this.properties = properties;
        validateConfiguredBounds();
    }

    /**
     * 生成可路由的内部 alarm_id。
     *
     * @param alarmBeginTime 报警开始时间，用于编码业务日期
     * @param sliceNo 月内容量分片序号，用于直达 alarm_yyyyMM_nn
     * @param rowNo 从 alarm_shard_slice Segment 预占得到的 slice 内行号
     * @param snowflakeSeed 现有 Snowflake 值，取低 9 位作为扰动字段
     * @return 63 位正数 Long ID
     */
    public long nextId(Date alarmBeginTime, int sliceNo, long rowNo, long snowflakeSeed) {
        if (alarmBeginTime == null) {
            throw new IllegalArgumentException("alarmBeginTime 不能为空");
        }
        if (sliceNo < 0 || sliceNo > MAX_SLICE_NO) {
            throw new IllegalArgumentException("sliceNo 超出 0..255 范围，sliceNo=" + sliceNo);
        }
        if (rowNo < 0 || rowNo > MAX_ROW_NO) {
            throw new IllegalArgumentException("rowNo 超出 0.." + MAX_ROW_NO + " 范围，rowNo=" + rowNo);
        }

        int workerId = properties.getId().getWorkerId();
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("alarm.sharding.id.workerId 必须在 0..255 之间，workerId=" + workerId);
        }

        long dayOffset = toDayOffset(alarmBeginTime);
        int seed = (int) (snowflakeSeed & MAX_SNOWFLAKE_SEED);

        return (dayOffset << DAY_SHIFT)
                | ((long) sliceNo << SLICE_SHIFT)
                | ((long) workerId << WORKER_SHIFT)
                | ((long) seed << SNOWFLAKE_SEED_SHIFT)
                | rowNo;
    }

    /**
     * 仅保留给旧测试或迁移期兼容调用。生产写入必须传入 DB Segment 分配的 rowNo。
     */
    @Deprecated
    public long nextId(Date alarmBeginTime, int sliceNo) {
        return nextId(alarmBeginTime, sliceNo, 0L, 0L);
    }

    public DecodedAlarmId decode(Long alarmId) {
        if (alarmId == null || alarmId <= 0) {
            return null;
        }

        long dayOffset = (alarmId >> DAY_SHIFT) & MAX_DAY_OFFSET;
        int sliceNo = (int) ((alarmId >> SLICE_SHIFT) & MAX_SLICE_NO);
        int workerId = (int) ((alarmId >> WORKER_SHIFT) & MAX_WORKER_ID);
        int snowflakeSeed = (int) ((alarmId >> SNOWFLAKE_SEED_SHIFT) & MAX_SNOWFLAKE_SEED);
        long rowNo = alarmId & MAX_ROW_NO;

        LocalDate alarmDate = EPOCH_DATE.plusDays(dayOffset);
        String monthKey = alarmDate.format(MONTH_FORMATTER);
        String tableSuffix = monthKey + "_" + String.format("%02d", sliceNo);
        Instant alarmInstant = alarmDate.atStartOfDay(ZoneId.systemDefault()).toInstant();

        DecodedAlarmId decoded = new DecodedAlarmId();
        decoded.setAlarmTime(Date.from(alarmInstant));
        decoded.setAlarmDate(alarmDate);
        decoded.setDayOffset(dayOffset);
        decoded.setMonthKey(monthKey);
        decoded.setSliceNo(sliceNo);
        decoded.setTableSuffix(tableSuffix);
        decoded.setWorkerId(workerId);
        decoded.setSnowflakeSeed(snowflakeSeed);
        decoded.setRowNo(rowNo);
        return decoded;
    }

    private long toDayOffset(Date alarmBeginTime) {
        LocalDate alarmDate = alarmBeginTime.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        long dayOffset = ChronoUnit.DAYS.between(EPOCH_DATE, alarmDate);
        if (dayOffset < 0 || dayOffset > MAX_DAY_OFFSET) {
            throw new IllegalArgumentException("alarmBeginTime 超出内部报警 ID 可编码日期范围，alarmBeginTime="
                    + alarmBeginTime);
        }
        return dayOffset;
    }

    private void validateConfiguredBounds() {
        if (properties.getMaxRowsPerSlice() <= 0) {
            throw new IllegalArgumentException("alarm.sharding.maxRowsPerSlice 必须大于 0，当前值="
                    + properties.getMaxRowsPerSlice());
        }
        if (properties.getMaxRowsPerSlice() > MAX_ROW_NO + 1) {
            throw new IllegalArgumentException("alarm.sharding.maxRowsPerSlice 最大不能超过 "
                    + (MAX_ROW_NO + 1) + "，当前值=" + properties.getMaxRowsPerSlice());
        }
        int workerId = properties.getId().getWorkerId();
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("alarm.sharding.id.workerId 必须在 0..255 之间，workerId=" + workerId);
        }
    }

    @Data
    public static class DecodedAlarmId {
        private Date alarmTime;
        private LocalDate alarmDate;
        private long dayOffset;
        private String monthKey;
        private int sliceNo;
        private String tableSuffix;
        private int workerId;
        private int snowflakeSeed;
        private long rowNo;
    }
}
