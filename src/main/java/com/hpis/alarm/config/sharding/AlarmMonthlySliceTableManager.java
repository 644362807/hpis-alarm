package com.hpis.alarm.config.sharding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * alarm 月内容量切片表管理器。
 *
 * <p>hpis-quad 的实现是一月一张表；hpis-alarm 数据量更高，因此这里在 yyyyMM 后追加
 * 两位月内序号 nn。写入时先按 alarm_beginTime 定位月份，再读取 alarm_shard_slice 的
 * 当前 active slice；如果 current_rows 达到 max_rows，就创建同月下一个子表。</p>
 *
 * <p>写入分配采用行业常见的 Segment 号段预占：本机从 alarm_shard_slice 批量预占一段 rowNo，
 * 后续报警在内存中消耗号段，只有号段耗尽时才进入 SELECT ... FOR UPDATE。DDL 使用
 * CREATE TABLE ... LIKE 复用现有逻辑表或月度分片表模板，减少手写 DDL 漏字段的风险。</p>
 */
@Slf4j
public class AlarmMonthlySliceTableManager {

    private static final List<String> SHARD_LOGIC_TABLES = Arrays.asList(
            "alarm", "alarm_handle", "alarm_electrolytic_cell");

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z0-9_]+");

    private final DataSource dataSource;

    private final AlarmShardProperties properties;

    private final ObjectProvider<AlarmShardingRuleRefreshService> refreshServiceProvider;

    private volatile boolean initialized;

    /**
     * 本机已预占但尚未消耗完的 rowNo 号段。
     */
    private final ConcurrentMap<String, LocalSegment> localSegments = new ConcurrentHashMap<>();

    /**
     * Segment 耗尽时才按月份加锁，不同月份可以并行预占。
     */
    private final ConcurrentMap<String, Object> monthLocks = new ConcurrentHashMap<>();

    /**
     * 已确认存在的物理表缓存，避免每条报警都查 DatabaseMetaData。
     */
    private final Set<String> confirmedPhysicalTables = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 已确认存在于 alarm_shard_slice 的 table_suffix 缓存，用于避免旧 Snowflake ID 被误解析后误路由。
     */
    private final Set<String> confirmedSliceSuffixes = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 首次创建物理表时按表名加锁，避免并发 DDL。
     */
    private final ConcurrentMap<String, Object> physicalTableLocks = new ConcurrentHashMap<>();

    public AlarmMonthlySliceTableManager(DataSource dataSource, AlarmShardProperties properties) {
        this(dataSource, properties, null);
    }

    public AlarmMonthlySliceTableManager(DataSource dataSource, AlarmShardProperties properties,
                                         ObjectProvider<AlarmShardingRuleRefreshService> refreshServiceProvider) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.refreshServiceProvider = refreshServiceProvider;
    }

    /**
     * 初始化元数据表并预建当前月及未来月份的 00 子表。
     */
    public void init() {
        createMetadataTables();
        preCreateMonthSlices();
        initialized = true;
    }

    /**
     * 为一条新报警分配物理表后缀。
     *
     * <p>本地 Segment 未耗尽时，该方法不会访问数据库；Segment 耗尽时才短暂锁住对应月份的
     * 最新切片并预占一段 rowNo。current_rows 表示已预占容量，不等于真实业务行数；服务异常
     * 退出时允许出现少量空洞，但不会影响路由正确性和主键唯一性。</p>
     */
    public ShardAllocation allocate(Date alarmBeginTime) {
        String monthKey = formatMonth(alarmBeginTime);
        while (true) {
            LocalSegment segment = localSegments.get(monthKey);
            Long rowNo = segment == null ? null : segment.nextRowNo();
            if (rowNo != null) {
                return new ShardAllocation(monthKey, segment.sliceNo, segment.tableSuffix, rowNo);
            }

            Object monthLock = monthLocks.computeIfAbsent(monthKey, key -> new Object());
            synchronized (monthLock) {
                segment = localSegments.get(monthKey);
                rowNo = segment == null ? null : segment.nextRowNo();
                if (rowNo != null) {
                    return new ShardAllocation(monthKey, segment.sliceNo, segment.tableSuffix, rowNo);
                }

                LocalSegment reservedSegment = reserveSegment(monthKey);
                localSegments.put(monthKey, reservedSegment);
                rowNo = reservedSegment.nextRowNo();
                if (rowNo == null) {
                    throw new IllegalStateException("预占分片号段为空，monthKey=" + monthKey
                            + ", tableSuffix=" + reservedSegment.tableSuffix);
                }
                return new ShardAllocation(monthKey, reservedSegment.sliceNo, reservedSegment.tableSuffix, rowNo);
            }
        }
    }

    public String allocateSuffix(Date alarmBeginTime) {
        return allocate(alarmBeginTime).getTableSuffix();
    }

    private LocalSegment reserveSegment(String monthKey) {
        LocalSegment segment;
        try (Connection connection = dataSource.getConnection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                SliceRow latest = selectLatestSliceForUpdate(connection, monthKey);
                if (latest == null) {
                    insertSlice(connection, monthKey, 0);
                    latest = selectSliceForUpdate(connection, monthKey, 0);
                }

                while (latest.currentRows >= latest.maxRows) {
                    int nextSliceNo = latest.sliceNo + 1;
                    assertEncodableSliceNo(nextSliceNo);
                    insertSlice(connection, monthKey, nextSliceNo);
                    latest = selectSliceForUpdate(connection, monthKey, nextSliceNo);
                }

                long remainingRows = latest.maxRows - latest.currentRows;
                long segmentSize = Math.min(getAllocationSegmentSize(), remainingRows);
                long startRowNo = latest.currentRows;
                long endRowNo = startRowNo + segmentSize;
                increaseCurrentRows(connection, latest.tableSuffix, segmentSize);
                connection.commit();
                connection.setAutoCommit(oldAutoCommit);
                segment = new LocalSegment(latest.sliceNo, latest.tableSuffix, startRowNo, endRowNo);
                confirmedSliceSuffixes.add(latest.tableSuffix);
            } catch (Exception ex) {
                connection.rollback();
                connection.setAutoCommit(oldAutoCommit);
                throw ex;
            }
        } catch (Exception ex) {
            throw new IllegalStateException("预占报警分片号段失败，monthKey=" + monthKey, ex);
        }

        createTablesForSuffix(segment.tableSuffix);
        log.debug("预占报警分片号段成功，monthKey={}, tableSuffix={}, startRowNo={}, endRowNo={}",
                monthKey, segment.tableSuffix, segment.startRowNo, segment.endRowNo);
        return segment;
    }

    /**
     * 根据时间范围列出需要查询的物理表。
     */
    public Set<String> listTablesByTimeRange(String logicTableName, Date startTime, Date endTime) {
        if (startTime == null && endTime == null) {
            return listAllShardTables(logicTableName);
        }

        YearMonth startMonth = startTime == null ? null : toYearMonth(startTime);
        YearMonth endMonth = endTime == null ? null : toYearMonth(endTime);
        if (startMonth != null && endMonth != null && startMonth.isAfter(endMonth)) {
            YearMonth tmp = startMonth;
            startMonth = endMonth;
            endMonth = tmp;
        }

        final YearMonth lowerMonth = startMonth;
        final YearMonth upperMonth = endMonth;
        Set<String> allTables = listAllShardTables(logicTableName);
        Set<String> result = allTables.stream()
                .filter(tableName -> isWithinMonthRange(logicTableName, tableName, lowerMonth, upperMonth))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return result.isEmpty() ? allTables : result;
    }

    private boolean isWithinMonthRange(String logicTableName, String tableName,
                                       YearMonth startMonth, YearMonth endMonth) {
        String prefix = logicTableName + "_";
        if (tableName == null || !tableName.startsWith(prefix) || tableName.length() < prefix.length() + 6) {
            return false;
        }
        try {
            YearMonth tableMonth = YearMonth.parse(
                    tableName.substring(prefix.length(), prefix.length() + 6), MONTH_FORMATTER);
            return (startMonth == null || !tableMonth.isBefore(startMonth))
                    && (endMonth == null || !tableMonth.isAfter(endMonth));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * 根据后缀集合转换为当前逻辑表对应的物理表名。
     */
    public Set<String> toPhysicalTables(String logicTableName, Set<String> suffixes) {
        if (suffixes == null || suffixes.isEmpty()) {
            return Collections.emptySet();
        }
        return suffixes.stream()
                .filter(suffix -> suffix != null && !"".equals(suffix.trim()))
                .map(suffix -> logicTableName + "_" + suffix)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean hasSlice(String tableSuffix) {
        if (tableSuffix == null || "".equals(tableSuffix.trim())) {
            return false;
        }
        if (confirmedSliceSuffixes.contains(tableSuffix)) {
            return true;
        }
        String sql = "select 1 from alarm_shard_slice where table_suffix = ? limit 1";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableSuffix);
            try (ResultSet resultSet = statement.executeQuery()) {
                boolean exists = resultSet.next();
                if (exists) {
                    confirmedSliceSuffixes.add(tableSuffix);
                }
                return exists;
            }
        } catch (SQLException ex) {
            log.warn("检查报警分片 suffix 是否存在失败，tableSuffix={}", tableSuffix, ex);
            return false;
        }
    }

    public Set<String> listAllShardTables(String logicTableName) {
        Set<String> tables = new LinkedHashSet<>();
        tables.addAll(listMonthlyPhysicalTables(logicTableName, null));
        return tables;
    }

    public Set<String> listActualDataNodeTables(String logicTableName) {
        return buildActualDataNodeTables(logicTableName, listMonthlyPhysicalTables(logicTableName, null), LocalDate.now());
    }

    public Set<String> buildActualDataNodeTables(String logicTableName, Set<String> existingPhysicalTables,
                                                 LocalDate baseDate) {
        assertSafeTableName(logicTableName);
        LocalDate currentMonth = (baseDate == null ? LocalDate.now() : baseDate).withDayOfMonth(1);
        Set<String> tables = new LinkedHashSet<>();
        String monthlyRegex = Pattern.quote(logicTableName) + "_\\d{6}_\\d{2}";
        if (existingPhysicalTables != null) {
            tables.addAll(existingPhysicalTables.stream()
                    .filter(tableName -> tableName != null && tableName.matches(monthlyRegex))
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
        }
        addPreRegisteredMonthTables(tables, logicTableName, currentMonth,
                properties.getActualDataNodes().safeCurrentMonthMaxSliceNo());
        addPreRegisteredMonthTables(tables, logicTableName, currentMonth.plusMonths(1),
                properties.getActualDataNodes().safeNextMonthMaxSliceNo());
        return tables;
    }

    public boolean createTablesForSuffix(String tableSuffix) {
        return createTablesForSuffix(tableSuffix, true);
    }

    private boolean createTablesForSuffix(String tableSuffix, boolean requestRefresh) {
        boolean created = false;
        for (String logicTableName : SHARD_LOGIC_TABLES) {
            created |= createPhysicalTableIfAbsent(logicTableName, logicTableName + "_" + tableSuffix);
        }
        if (created && initialized && requestRefresh) {
            requestRuleRefreshAfterTableCreated(tableSuffix);
        }
        return created;
    }

    private void addPreRegisteredMonthTables(Set<String> tables, String logicTableName, LocalDate month,
                                             int maxSliceNo) {
        String monthKey = month.format(MONTH_FORMATTER);
        for (int sliceNo = 0; sliceNo <= maxSliceNo; sliceNo++) {
            tables.add(logicTableName + "_" + buildSuffix(monthKey, sliceNo));
        }
    }

    private void preCreateMonthSlices() {
        LocalDate firstDayOfMonth = LocalDate.now().withDayOfMonth(1);
        for (int i = 0; i <= properties.getPreCreateMonths(); i++) {
            String monthKey = firstDayOfMonth.plusMonths(i).format(MONTH_FORMATTER);
            ensureSlice(monthKey, 0, false);
        }
    }

    public boolean preCreateNextMonthSlices(LocalDate baseDate, int maxSliceNo) {
        LocalDate firstDayOfMonth = (baseDate == null ? LocalDate.now() : baseDate).withDayOfMonth(1);
        String nextMonthKey = firstDayOfMonth.plusMonths(1).format(MONTH_FORMATTER);
        int safeMaxSliceNo = Math.max(0, Math.min(maxSliceNo, 255));
        boolean created = false;
        for (int sliceNo = 0; sliceNo <= safeMaxSliceNo; sliceNo++) {
            created |= ensureSlice(nextMonthKey, sliceNo, false);
        }
        return created;
    }

    private boolean ensureSlice(String monthKey, int sliceNo) {
        return ensureSlice(monthKey, sliceNo, true);
    }

    private boolean ensureSlice(String monthKey, int sliceNo, boolean requestRefresh) {
        String suffix = buildSuffix(monthKey, sliceNo);
        boolean created = createTablesForSuffix(suffix, requestRefresh);
        try (Connection connection = dataSource.getConnection()) {
            insertSlice(connection, monthKey, sliceNo);
            confirmedSliceSuffixes.add(suffix);
        } catch (SQLException ex) {
            throw new IllegalStateException("初始化报警分片元数据失败，monthKey=" + monthKey, ex);
        }
        return created;
    }

    private void createMetadataTables() {
        String createSliceSql = "CREATE TABLE IF NOT EXISTS alarm_shard_slice ("
                + "id bigint NOT NULL AUTO_INCREMENT,"
                + "month_key varchar(6) NOT NULL,"
                + "slice_no int NOT NULL,"
                + "table_suffix varchar(16) NOT NULL,"
                + "current_rows bigint NOT NULL DEFAULT 0,"
                + "max_rows bigint NOT NULL,"
                + "status varchar(16) NOT NULL DEFAULT 'ACTIVE',"
                + "created_time datetime DEFAULT CURRENT_TIMESTAMP,"
                + "updated_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_alarm_shard_slice_month_no (month_key, slice_no),"
                + "UNIQUE KEY uk_alarm_shard_slice_suffix (table_suffix)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警月内容量分片元数据'";

        String createHotCidSql = "CREATE TABLE IF NOT EXISTS alarm_cid_index ("
                + "id bigint NOT NULL AUTO_INCREMENT,"
                + "alarm_cid varchar(128) NOT NULL,"
                + "alarm_id bigint NOT NULL,"
                + "alarm_beginTime datetime NOT NULL,"
                + "alarm_endTime datetime DEFAULT NULL,"
                + "table_suffix varchar(16) NOT NULL,"
                + "device_sn varchar(128) DEFAULT NULL,"
                + "irms_sn varchar(128) DEFAULT NULL,"
                + "alarm_type varchar(32) DEFAULT NULL,"
                + "route_status varchar(16) NOT NULL DEFAULT 'ACTIVE',"
                + "delete_after datetime DEFAULT NULL,"
                + "created_time datetime DEFAULT CURRENT_TIMESTAMP,"
                + "updated_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_alarm_cid (alarm_cid),"
                + "UNIQUE KEY uk_alarm_id (alarm_id),"
                + "KEY idx_status_begin_time (route_status, alarm_beginTime),"
                + "KEY idx_status_delete_after (route_status, delete_after),"
                + "KEY idx_device_status (device_sn, route_status),"
                + "KEY idx_irms_status (irms_sn, route_status)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警外部ID热点路由表'";

        String createStaleCidSql = "CREATE TABLE IF NOT EXISTS alarm_cid_stale_index ("
                + "id bigint NOT NULL AUTO_INCREMENT,"
                + "alarm_cid varchar(128) NOT NULL,"
                + "alarm_id bigint NOT NULL,"
                + "alarm_beginTime datetime NOT NULL,"
                + "alarm_endTime datetime DEFAULT NULL,"
                + "table_suffix varchar(16) NOT NULL,"
                + "device_sn varchar(128) DEFAULT NULL,"
                + "irms_sn varchar(128) DEFAULT NULL,"
                + "alarm_type varchar(32) DEFAULT NULL,"
                + "route_status varchar(16) NOT NULL DEFAULT 'ACTIVE',"
                + "stale_time datetime NOT NULL,"
                + "expire_time datetime NOT NULL,"
                + "delete_after datetime DEFAULT NULL,"
                + "created_time datetime DEFAULT CURRENT_TIMESTAMP,"
                + "updated_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_alarm_cid (alarm_cid),"
                + "UNIQUE KEY uk_alarm_id (alarm_id),"
                + "KEY idx_status_begin_time (route_status, alarm_beginTime),"
                + "KEY idx_status_delete_after (route_status, delete_after),"
                + "KEY idx_device_status (device_sn, route_status),"
                + "KEY idx_irms_status (irms_sn, route_status),"
                + "KEY idx_expire_time (expire_time)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警外部ID滞留路由表'";

        String createStopEventSql = "CREATE TABLE IF NOT EXISTS alarm_stop_event ("
                + "id bigint NOT NULL AUTO_INCREMENT,"
                + "alarm_cid varchar(128) NOT NULL,"
                + "stop_time datetime NOT NULL,"
                + "event_version bigint NOT NULL DEFAULT 0,"
                + "applied_stop_time datetime DEFAULT NULL,"
                + "event_status varchar(16) NOT NULL DEFAULT 'PENDING',"
                + "retry_count int NOT NULL DEFAULT 0,"
                + "last_error varchar(1024) DEFAULT NULL,"
                + "lock_token varchar(64) DEFAULT NULL,"
                + "locked_at datetime DEFAULT NULL,"
                + "available_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "created_time datetime DEFAULT CURRENT_TIMESTAMP,"
                + "updated_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "applied_time datetime DEFAULT NULL,"
                + "delete_after datetime DEFAULT NULL,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_alarm_stop_event_cid (alarm_cid),"
                + "KEY idx_stop_event_status_time (event_status, created_time),"
                + "KEY idx_stop_event_claim (event_status, available_time, created_time, id),"
                + "KEY idx_stop_event_processing_timeout (event_status, locked_at),"
                + "KEY idx_stop_event_delete_after (event_status, delete_after)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='alarm stop event reliable buffer'";

        String createSideEffectSql = "CREATE TABLE IF NOT EXISTS alarm_stop_side_effect_event ("
                + "id bigint NOT NULL AUTO_INCREMENT,"
                + "alarm_id bigint NOT NULL,"
                + "alarm_cid varchar(128) DEFAULT NULL,"
                + "table_suffix varchar(16) NOT NULL,"
                + "effect_type varchar(64) NOT NULL,"
                + "payload_json text DEFAULT NULL,"
                + "effect_status varchar(16) NOT NULL DEFAULT 'PENDING',"
                + "retry_count int NOT NULL DEFAULT 0,"
                + "last_error varchar(1024) DEFAULT NULL,"
                + "created_time datetime DEFAULT CURRENT_TIMESTAMP,"
                + "updated_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_alarm_effect (alarm_id, effect_type),"
                + "KEY idx_effect_status_time (effect_status, created_time)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='alarm stop side effect event'";

        String createElectrolyticSnapshotCommandSql =
                "CREATE TABLE IF NOT EXISTS alarm_electrolytic_cell_snapshot_command ("
                + "point_hash varchar(64) NOT NULL,"
                + "command_type varchar(16) NOT NULL,"
                + "alarm_id bigint NOT NULL,"
                + "alarm_begin_time datetime DEFAULT NULL,"
                + "payload_json text NOT NULL,"
                + "command_status varchar(16) NOT NULL DEFAULT 'PENDING',"
                + "lock_token varchar(64) DEFAULT NULL,"
                + "locked_at datetime DEFAULT NULL,"
                + "available_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "version bigint NOT NULL DEFAULT 0,"
                + "retry_count int NOT NULL DEFAULT 0,"
                + "last_error varchar(1024) DEFAULT NULL,"
                + "created_time datetime DEFAULT CURRENT_TIMESTAMP,"
                + "updated_time datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (point_hash),"
                + "KEY idx_ec_snapshot_claim (command_status, available_time, updated_time, point_hash),"
                + "KEY idx_ec_snapshot_processing_timeout (command_status, locked_at),"
                + "KEY idx_ec_snapshot_alarm_id (alarm_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='electrolytic cell current snapshot projection command'";

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(createSliceSql);
            statement.execute(createHotCidSql);
            statement.execute(createStaleCidSql);
            statement.execute(createStopEventSql);
            statement.execute(createSideEffectSql);
            statement.execute(createElectrolyticSnapshotCommandSql);
        } catch (SQLException ex) {
            throw new IllegalStateException("创建报警分片元数据表失败", ex);
        }
    }

    private SliceRow selectLatestSliceForUpdate(Connection connection, String monthKey) throws SQLException {
        String sql = "select slice_no, table_suffix, current_rows, max_rows "
                + "from alarm_shard_slice where month_key = ? order by slice_no desc limit 1 for update";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, monthKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new SliceRow(
                        resultSet.getInt("slice_no"),
                        resultSet.getString("table_suffix"),
                        resultSet.getLong("current_rows"),
                        resultSet.getLong("max_rows"));
            }
        }
    }

    private SliceRow selectSliceForUpdate(Connection connection, String monthKey, int sliceNo) throws SQLException {
        String sql = "select slice_no, table_suffix, current_rows, max_rows "
                + "from alarm_shard_slice where month_key = ? and slice_no = ? for update";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, monthKey);
            statement.setInt(2, sliceNo);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("未找到刚创建的报警分片元数据，monthKey=" + monthKey
                            + ", sliceNo=" + sliceNo);
                }
                return new SliceRow(
                        resultSet.getInt("slice_no"),
                        resultSet.getString("table_suffix"),
                        resultSet.getLong("current_rows"),
                        resultSet.getLong("max_rows"));
            }
        }
    }

    private void insertSlice(Connection connection, String monthKey, int sliceNo) throws SQLException {
        String suffix = buildSuffix(monthKey, sliceNo);
        String sql = "insert ignore into alarm_shard_slice "
                + "(month_key, slice_no, table_suffix, current_rows, max_rows) "
                + "values (?, ?, ?, 0, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, monthKey);
            statement.setInt(2, sliceNo);
            statement.setString(3, suffix);
            statement.setLong(4, properties.getMaxRowsPerSlice());
            statement.executeUpdate();
        }
    }

    private void increaseCurrentRows(Connection connection, String tableSuffix, long rows) throws SQLException {
        String sql = "update alarm_shard_slice set current_rows = current_rows + ? "
                + "where table_suffix = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rows);
            statement.setString(2, tableSuffix);
            statement.executeUpdate();
        }
    }

    private boolean createPhysicalTableIfAbsent(String logicTableName, String physicalTableName) {
        if (confirmedPhysicalTables.contains(physicalTableName)) {
            return false;
        }
        Object tableLock = physicalTableLocks.computeIfAbsent(physicalTableName, key -> new Object());
        synchronized (tableLock) {
            if (confirmedPhysicalTables.contains(physicalTableName)) {
                return false;
            }
            boolean created = createPhysicalTable(logicTableName, physicalTableName);
            confirmedPhysicalTables.add(physicalTableName);
            return created;
        }
    }

    private boolean createPhysicalTable(String logicTableName, String physicalTableName) {
        assertSafeTableName(logicTableName);
        assertSafeTableName(physicalTableName);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            boolean created = false;
            if (!tableExists(connection, physicalTableName)) {
                String templateTable = findTemplateTable(connection, logicTableName);
                if (templateTable == null) {
                    throw new IllegalStateException("未找到报警分片模板表：" + logicTableName);
                }
                statement.execute("CREATE TABLE IF NOT EXISTS " + physicalTableName + " LIKE " + templateTable);
                created = true;
                log.info("创建报警分片物理表成功，logicTableName={}, physicalTableName={}, templateTable={}",
                        logicTableName, physicalTableName, templateTable);
            }
            ensureExtraColumns(connection, statement, logicTableName, physicalTableName);
            return created;
        } catch (SQLException ex) {
            throw new IllegalStateException("创建报警分片物理表失败：" + physicalTableName, ex);
        }
    }

    private void requestRuleRefreshAfterTableCreated(String tableSuffix) {
        if (refreshServiceProvider == null) {
            return;
        }
        AlarmShardingRuleRefreshService refreshService = refreshServiceProvider.getIfAvailable();
        if (refreshService == null) {
            return;
        }
        refreshService.requestRefreshAfterCommit("table-created:" + tableSuffix);
    }

    private void ensureExtraColumns(Connection connection, Statement statement, String logicTableName,
                                    String physicalTableName) throws SQLException {
        if ("alarm_handle".equals(logicTableName) || "alarm_electrolytic_cell".equals(logicTableName)) {
            if (!columnExists(connection, physicalTableName, "alarm_beginTime")) {
                statement.execute("ALTER TABLE " + physicalTableName
                        + " ADD COLUMN alarm_beginTime datetime DEFAULT NULL COMMENT '报警开始时间，用于时间分片路由'");
            }
        }

        if ("alarm_electrolytic_cell".equals(logicTableName)
                && !columnExists(connection, physicalTableName, "del_flag")) {
            statement.execute("ALTER TABLE " + physicalTableName
                    + " ADD COLUMN del_flag char(2) NOT NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）'");
        }
    }

    private String findTemplateTable(Connection connection, String logicTableName) throws SQLException {
        if (tableExists(connection, logicTableName)) {
            return logicTableName;
        }
        Set<String> tables = listTables(connection, logicTableName + "_%");
        return tables.stream()
                .filter(tableName -> tableName.matches(Pattern.quote(logicTableName) + "_\\d{6}_\\d{2}"))
                .findFirst()
                .orElse(null);
    }

    private Set<String> listTablesByMonth(String logicTableName, String monthKey) {
        Set<String> tables = new LinkedHashSet<>();
        tables.addAll(listMonthlyPhysicalTables(logicTableName, monthKey));
        return tables;
    }

    private Set<String> listMonthlyPhysicalTables(String logicTableName, String monthKey) {
        try (Connection connection = dataSource.getConnection()) {
            Set<String> tables = listTables(connection, logicTableName + "_%");
            String regex = monthKey == null
                    ? Pattern.quote(logicTableName) + "_\\d{6}_\\d{2}"
                    : Pattern.quote(logicTableName) + "_" + monthKey + "_\\d{2}";
            return tables.stream()
                    .filter(tableName -> tableName.matches(regex))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (SQLException ex) {
            log.warn("读取报警月分片物理表失败，logicTableName={}, monthKey={}", logicTableName, monthKey, ex);
            return Collections.emptySet();
        }
    }

    private Set<String> listTables(Connection connection, String tablePattern) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        Set<String> tables = new LinkedHashSet<>();
        try (ResultSet resultSet = metaData.getTables(connection.getCatalog(), null, tablePattern, new String[]{"TABLE"})) {
            while (resultSet.next()) {
                tables.add(resultSet.getString("TABLE_NAME"));
            }
        }
        return tables;
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData()
                .getTables(connection.getCatalog(), null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData()
                .getColumns(connection.getCatalog(), null, tableName, columnName)) {
            return resultSet.next();
        }
    }

    private String formatMonth(Date date) {
        return new SimpleDateFormat("yyyyMM").format(date);
    }

    private String buildSuffix(String monthKey, int sliceNo) {
        return monthKey + "_" + String.format("%02d", sliceNo);
    }

    private long getAllocationSegmentSize() {
        return Math.max(1, properties.getAllocationSegmentSize());
    }

    private void assertEncodableSliceNo(int sliceNo) {
        if (sliceNo > 255) {
            throw new IllegalStateException("sliceNo 超出 alarm_id v2 可编码范围 0..255，sliceNo=" + sliceNo);
        }
    }

    private YearMonth toYearMonth(Date date) {
        return YearMonth.from(date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate());
    }

    private void assertSafeTableName(String tableName) {
        if (!SAFE_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException("非法表名：" + tableName);
        }
    }

    private static final class SliceRow {
        private final int sliceNo;
        private final String tableSuffix;
        private final long currentRows;
        private final long maxRows;

        private SliceRow(int sliceNo, String tableSuffix, long currentRows, long maxRows) {
            this.sliceNo = sliceNo;
            this.tableSuffix = tableSuffix;
            this.currentRows = currentRows;
            this.maxRows = maxRows;
        }
    }

    private static final class LocalSegment {
        private final int sliceNo;
        private final String tableSuffix;
        private final long startRowNo;
        private final long endRowNo;
        private final AtomicLong nextRowNo;

        private LocalSegment(int sliceNo, String tableSuffix, long startRowNo, long endRowNo) {
            this.sliceNo = sliceNo;
            this.tableSuffix = tableSuffix;
            this.startRowNo = startRowNo;
            this.endRowNo = endRowNo;
            this.nextRowNo = new AtomicLong(startRowNo);
        }

        private Long nextRowNo() {
            long value = nextRowNo.getAndIncrement();
            return value < endRowNo ? value : null;
        }
    }

    public static final class ShardAllocation {
        private final String monthKey;
        private final int sliceNo;
        private final String tableSuffix;
        private final long rowNo;

        private ShardAllocation(String monthKey, int sliceNo, String tableSuffix, long rowNo) {
            this.monthKey = monthKey;
            this.sliceNo = sliceNo;
            this.tableSuffix = tableSuffix;
            this.rowNo = rowNo;
        }

        public String getMonthKey() {
            return monthKey;
        }

        public int getSliceNo() {
            return sliceNo;
        }

        public String getTableSuffix() {
            return tableSuffix;
        }

        public long getRowNo() {
            return rowNo;
        }
    }
}
