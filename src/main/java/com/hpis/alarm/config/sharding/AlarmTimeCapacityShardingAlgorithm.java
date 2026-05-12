package com.hpis.alarm.config.sharding;

import com.google.common.collect.Range;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.api.sharding.complex.ComplexKeysShardingAlgorithm;
import org.apache.shardingsphere.api.sharding.complex.ComplexKeysShardingValue;

import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * alarm 时间 + 容量复合分片算法。
 *
 * <p>旧 inline 规则只能表达 alarm_id % 5 这种固定取模，无法表达“先按月份，再按月内
 * 行数阈值切子表”的动态决策，所以这里改用 ComplexKeysShardingAlgorithm。算法支持
 * 多种旧接口常见路由方式：</p>
 *
 * <p>1. 写入路径：优先读取 AlarmShardContext 中的 table_suffix，保证主表、处理表、
 * 电解槽表落在同一个物理分片。</p>
 *
 * <p>2. 精确 ID：优先解析新的内部 alarm_id，直接得到 yyyyMM_nn。外部 cid 只查
 * hot/stale 生命周期索引，不再依赖全量历史路由表。</p>
 *
 * <p>3. 时间范围：按 alarm_beginTime 推导月份，并命中这些月份下已经存在的所有子表。</p>
 *
 * <p>4. 设备 / 网关停止报警：通过 hot/stale 中 ACTIVE 的路由元数据找到仍未结束的报警分片。</p>
 */
@Slf4j
public class AlarmTimeCapacityShardingAlgorithm implements ComplexKeysShardingAlgorithm<Comparable<?>> {

    private static final String COLUMN_ALARM_ID = "alarm_id";

    private static final String COLUMN_ALARM_CID = "alarm_cid";

    private static final String COLUMN_DEVICE_SN = "device_sn";

    private static final String COLUMN_IRMS_SN = "irms_sn";

    private static final String COLUMN_ALARM_BEGIN_TIME = "alarm_beginTime";

    private final AlarmMonthlySliceTableManager tableManager;

    private final AlarmCidIndexRepository cidIndexRepository;

    private final AlarmIdCodec alarmIdCodec;

    public AlarmTimeCapacityShardingAlgorithm(AlarmMonthlySliceTableManager tableManager,
                                              AlarmCidIndexRepository cidIndexRepository,
                                              AlarmIdCodec alarmIdCodec) {
        this.tableManager = tableManager;
        this.cidIndexRepository = cidIndexRepository;
        this.alarmIdCodec = alarmIdCodec;
    }

    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames,
                                         ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        String logicTableName = shardingValue.getLogicTableName();

        String contextSuffix = AlarmShardContext.getTableSuffix();
        if (contextSuffix != null && !"".equals(contextSuffix.trim())) {
            return Collections.singleton(logicTableName + "_" + contextSuffix);
        }

        Set<String> routeById = routeByAlarmIds(logicTableName, shardingValue);
        if (!routeById.isEmpty()) {
            return routeById;
        }

        Set<String> routeByCid = routeByAlarmCids(logicTableName, shardingValue);
        if (!routeByCid.isEmpty()) {
            return routeByCid;
        }

        Set<String> routeByTime = routeByTime(logicTableName, shardingValue);
        if (!routeByTime.isEmpty()) {
            return routeByTime;
        }

        Set<String> routeByDevice = routeByActiveDevice(logicTableName, shardingValue);
        if (!routeByDevice.isEmpty()) {
            return routeByDevice;
        }

        Set<String> routeByIrms = routeByActiveIrms(logicTableName, shardingValue);
        if (!routeByIrms.isEmpty()) {
            return routeByIrms;
        }

        Set<String> fallback = tableManager.listAllShardTables(logicTableName);
        if (!fallback.isEmpty()) {
            log.debug("报警分片未命中明确分片键，回退到全部已知分片，logicTableName={}, tables={}",
                    logicTableName, fallback);
            return fallback;
        }
        return availableTargetNames;
    }

    private Set<String> routeByAlarmIds(String logicTableName,
                                        ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<Long> alarmIds = findValues(shardingValue.getColumnNameAndShardingValuesMap(), COLUMN_ALARM_ID)
                .stream()
                .map(this::toLong)
                .filter(value -> value != null)
                .collect(Collectors.toList());
        Set<String> suffixes = new LinkedHashSet<>();
        for (Long alarmId : alarmIds) {
            AlarmIdCodec.DecodedAlarmId decoded = alarmIdCodec.decode(alarmId);
            if (decoded != null && tableManager.hasSlice(decoded.getTableSuffix())) {
                suffixes.add(decoded.getTableSuffix());
            }
        }
        suffixes.addAll(cidIndexRepository.findSuffixesByAlarmIds(alarmIds));
        Set<String> routedTables = tableManager.toPhysicalTables(logicTableName, suffixes);
        routedTables.addAll(tableManager.toLegacyPhysicalTablesByAlarmIds(logicTableName, alarmIds));
        return routedTables;
    }

    private Set<String> routeByAlarmCids(String logicTableName,
                                         ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<String> alarmCids = findValues(shardingValue.getColumnNameAndShardingValuesMap(), COLUMN_ALARM_CID)
                .stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
        return tableManager.toPhysicalTables(logicTableName, cidIndexRepository.findSuffixesByAlarmCids(alarmCids));
    }

    private Set<String> routeByActiveDevice(String logicTableName,
                                            ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<String> deviceSns = findValues(shardingValue.getColumnNameAndShardingValuesMap(), COLUMN_DEVICE_SN)
                .stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
        return tableManager.toPhysicalTables(logicTableName, cidIndexRepository.findActiveSuffixesByDeviceSns(deviceSns));
    }

    private Set<String> routeByActiveIrms(String logicTableName,
                                          ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        List<String> irmsSns = findValues(shardingValue.getColumnNameAndShardingValuesMap(), COLUMN_IRMS_SN)
                .stream()
                .map(String::valueOf)
                .collect(Collectors.toList());
        return tableManager.toPhysicalTables(logicTableName, cidIndexRepository.findActiveSuffixesByIrmsSns(irmsSns));
    }

    private Set<String> routeByTime(String logicTableName,
                                    ComplexKeysShardingValue<Comparable<?>> shardingValue) {
        Range<Comparable<?>> range = findRange(shardingValue.getColumnNameAndRangeValuesMap(), COLUMN_ALARM_BEGIN_TIME);
        if (range != null) {
            Date start = range.hasLowerBound() ? toDate(range.lowerEndpoint()) : null;
            Date end = range.hasUpperBound() ? toDate(range.upperEndpoint()) : null;
            return tableManager.listTablesByTimeRange(logicTableName, start, end);
        }

        List<Date> exactTimes = findValues(shardingValue.getColumnNameAndShardingValuesMap(), COLUMN_ALARM_BEGIN_TIME)
                .stream()
                .map(this::toDate)
                .filter(value -> value != null)
                .collect(Collectors.toList());
        if (exactTimes.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> tables = new LinkedHashSet<>();
        for (Date exactTime : exactTimes) {
            tables.addAll(tableManager.listTablesByTimeRange(logicTableName, exactTime, exactTime));
        }
        return tables;
    }

    private Collection<Comparable<?>> findValues(Map<String, Collection<Comparable<?>>> valuesMap, String columnName) {
        if (valuesMap == null || valuesMap.isEmpty()) {
            return Collections.emptyList();
        }
        for (Map.Entry<String, Collection<Comparable<?>>> entry : valuesMap.entrySet()) {
            if (columnName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue() == null ? Collections.emptyList() : entry.getValue();
            }
        }
        return Collections.emptyList();
    }

    private Range<Comparable<?>> findRange(Map<String, Range<Comparable<?>>> rangeMap, String columnName) {
        if (rangeMap == null || rangeMap.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, Range<Comparable<?>>> entry : rangeMap.entrySet()) {
            if (columnName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ex) {
            log.warn("报警分片 alarm_id 无法转换为 Long，value={}", value);
            return null;
        }
    }

    private Date toDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Date) {
            return (Date) value;
        }
        if (value instanceof Timestamp) {
            return new Date(((Timestamp) value).getTime());
        }
        String text = String.valueOf(value);
        List<String> patterns = new ArrayList<>();
        patterns.add("yyyy-MM-dd HH:mm:ss");
        patterns.add("yyyy-MM-dd");
        for (String pattern : patterns) {
            try {
                return new SimpleDateFormat(pattern).parse(text);
            } catch (ParseException ignored) {
                // 尝试下一个时间格式。
            }
        }
        log.warn("报警分片 alarm_beginTime 无法转换为 Date，value={}", value);
        return null;
    }
}
