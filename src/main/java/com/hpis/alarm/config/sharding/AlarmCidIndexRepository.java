package com.hpis.alarm.config.sharding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分片算法专用 cid 路由读取器。
 *
 * <p>ShardingSphere 算法执行时不能再走分片数据源查询路由表，否则容易产生嵌套路由。
 * 因此这里直接使用物理 DataSource 读取 alarm_cid_index/alarm_cid_stale_index。两张表
 * 只保存热点和滞留数据，不承担永久历史查询能力。</p>
 */
@Slf4j
public class AlarmCidIndexRepository {

    private final JdbcTemplate jdbcTemplate;

    public AlarmCidIndexRepository(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public Set<String> findSuffixesByAlarmIds(Collection<Long> alarmIds) {
        return querySuffixes("alarm_id", alarmIds, false);
    }

    public Set<String> findSuffixesByAlarmCids(Collection<String> alarmCids) {
        return querySuffixes("alarm_cid", alarmCids, false);
    }

    public Set<String> findActiveSuffixesByDeviceSns(Collection<String> deviceSns) {
        return querySuffixes("device_sn", deviceSns, true);
    }

    public Set<String> findActiveSuffixesByIrmsSns(Collection<String> irmsSns) {
        return querySuffixes("irms_sn", irmsSns, true);
    }

    private Set<String> querySuffixes(String columnName, Collection<?> values, boolean activeOnly) {
        List<?> cleanValues = values == null ? Collections.emptyList() : values.stream()
                .filter(value -> value != null && !"".equals(String.valueOf(value).trim()))
                .collect(Collectors.toList());
        if (cleanValues.isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> suffixes = new LinkedHashSet<>();
        suffixes.addAll(querySuffixesFromTable("alarm_cid_index", columnName, cleanValues, activeOnly));
        suffixes.addAll(querySuffixesFromTable("alarm_cid_stale_index", columnName, cleanValues, activeOnly));
        return suffixes;
    }

    private Set<String> querySuffixesFromTable(String tableName, String columnName, List<?> values, boolean activeOnly) {
        String placeholders = values.stream().map(value -> "?").collect(Collectors.joining(","));
        String sql = "select distinct table_suffix from " + tableName + " where " + columnName
                + " in (" + placeholders + ")";
        if (activeOnly) {
            sql = sql + " and route_status = 'ACTIVE'";
        }

        try {
            List<Object> args = new ArrayList<>(values);
            return new LinkedHashSet<>(jdbcTemplate.queryForList(sql, args.toArray(), String.class));
        } catch (DataAccessException ex) {
            log.warn("查询报警 cid 路由索引失败，tableName={}, columnName={}, values={}",
                    tableName, columnName, values, ex);
            return Collections.emptySet();
        }
    }
}
