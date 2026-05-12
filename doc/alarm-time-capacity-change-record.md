# hpis-alarm 时间 + 容量分片整体修改记录

## 文档信息

| 项目 | 内容 |
| --- | --- |
| 模块 | `hpis-alarm` |
| 修改日期 | 2026-04-30 |
| 目标版本 | 保留 ShardingSphere 4.1.1 |
| 参考实现 | `hpis-quad` ShardingSphere 4.1.0 Java API 动态月表方案 |
| 修改范围 | 仅 `hpis-alarm`，不直接修改 `hpis-quad` |

## 修改目标

原报警分片规则为固定取模：

```yaml
alarm_$->{alarm_id % 5}
alarm_handle_$->{alarm_id % 5}
alarm_electrolytic_cell_$->{alarm_id % 5}
```

本次目标是改为时间 + 容量分片：

- 先按 `alarm_beginTime` 分月份。
- 物理表格式为 `alarm_yyyyMM_nn`、`alarm_handle_yyyyMM_nn`、`alarm_electrolytic_cell_yyyyMM_nn`。
- 同月单表超过 `alarm.sharding.maxRowsPerSlice` 后，切换到下一个月内子表，例如 `202604_00` 到 `202604_01`。
- 原 Controller 入参和返回结构保持不变。
- 旧接口按 `alarm_id`、`alarm_cid`、`device_sn`、`irms_sn` 查询或停止时，通过路由元数据表保持兼容。

## 核心设计

### 分片规则

| 逻辑表 | 新物理表格式 | 示例 |
| --- | --- | --- |
| `alarm` | `alarm_yyyyMM_nn` | `alarm_202604_00` |
| `alarm_handle` | `alarm_handle_yyyyMM_nn` | `alarm_handle_202604_00` |
| `alarm_electrolytic_cell` | `alarm_electrolytic_cell_yyyyMM_nn` | `alarm_electrolytic_cell_202604_00` |

三张表继续作为绑定表处理，新增报警时由同一个 `table_suffix` 保证落到同一组物理表。

### 元数据表

| 表名 | 作用 |
| --- | --- |
| `alarm_shard_slice` | 记录月份、月内子表序号、表后缀、当前行数、最大行数，用于容量切片。 |
| `alarm_shard_route` | 记录业务键到表后缀的映射，用于旧接口精准路由。 |

### 最小字段补充

| 表 | 字段变更 | 原因 |
| --- | --- | --- |
| `alarm` | 不新增，复用已有 `alarm_beginTime` | 主表已有分片时间字段。 |
| `alarm_handle` | 新增 `alarm_beginTime` | 处理表需要参与同 suffix 路由。 |
| `alarm_electrolytic_cell` | 新增 `alarm_beginTime` | 电解槽表需要参与同 suffix 路由。 |

## 新增代码

### 分片配置与算法

| 文件 | 说明 |
| --- | --- |
| `src/main/java/com/hpis/alarm/config/sharding/AlarmShardProperties.java` | `alarm.sharding` 配置项，默认关闭新分片。 |
| `src/main/java/com/hpis/alarm/config/sharding/AlarmDynamicShardingConfig.java` | 通过 ShardingSphere 4.1.1 Java API 创建分片数据源。 |
| `src/main/java/com/hpis/alarm/config/sharding/AlarmTimeCapacityShardingAlgorithm.java` | 复合分片算法，支持 ThreadLocal 写入、ID/CID 精确路由、时间范围路由、设备/IRMS 活跃路由。 |
| `src/main/java/com/hpis/alarm/config/sharding/AlarmMonthlySliceTableManager.java` | 创建元数据表、动态建月表、按行数阈值分配月内子表。 |
| `src/main/java/com/hpis/alarm/config/sharding/AlarmShardContext.java` | 写入链路 ThreadLocal，用于绑定三张表同一个 `table_suffix`。 |
| `src/main/java/com/hpis/alarm/config/sharding/AlarmShardRouteRepository.java` | 分片算法专用元数据读取器，直接走物理数据源避免递归路由。 |
| `src/main/java/com/hpis/alarm/config/sharding/AlarmShardRouteService.java` | 业务写入层的路由服务，负责分配 suffix、写入路由、更新 active 状态。 |

### 元数据模型与 Mapper

| 文件 | 说明 |
| --- | --- |
| `src/main/java/com/hpis/alarm/domain/AlarmShardRoute.java` | `alarm_shard_route` 实体。 |
| `src/main/java/com/hpis/alarm/domain/AlarmShardSlice.java` | `alarm_shard_slice` 实体。 |
| `src/main/java/com/hpis/alarm/mapper/AlarmShardRouteMapper.java` | 路由元数据写入和 active 状态更新 Mapper。 |
| `src/main/resources/mapper/alarm/AlarmShardRouteMapper.xml` | `alarm_shard_route` upsert 和停止更新 SQL。 |

## 修改代码

| 文件 | 修改内容 |
| --- | --- |
| `src/main/java/com/hpis/alarm/service/impl/AlarmServiceImpl.java` | 新增报警前分配 `table_suffix`；写入三张绑定表时使用 ThreadLocal 同分片；主表写入后保存 `alarm_shard_route`；重复报警逻辑延后到事务提交后执行；停止接口同步维护路由 active 状态。 |
| `src/main/java/com/hpis/alarm/domain/AlarmElectrolyticCell.java` | 新增 `alarmBegintime` 字段，对应 `alarm_electrolytic_cell.alarm_beginTime`。 |
| `src/main/java/com/hpis/alarm/mapper/AlarmMapper.java` | 修正 `alarmStopByDeviceId` 的 `@Param` 名称为 `deviceSn`，与 XML 保持一致。 |
| `src/main/resources/mapper/alarm/AlarmMapper.xml` | 时间统计类 SQL 增加真实时间范围条件，降低全分片扫描风险。 |
| `src/main/resources/mapper/alarm/AlarmHandleMapper.xml` | `insertAlarmHandle` 和批量插入写入 `alarm_beginTime`。 |
| `src/main/resources/mapper/alarm/AlarmElectrolyticCellMapper.xml` | 结果映射、查询字段和新增插入逻辑支持 `alarm_beginTime`。 |

## SQL 与数据库文件

| 文件 | 说明 |
| --- | --- |
| `src/main/resources/sql/alarm-time-capacity-sharding.sql` | 基础 DDL：创建 `alarm_shard_slice`、`alarm_shard_route`，幂等补齐 `alarm_beginTime` 字段。 |
| `src/main/resources/sql/alarm-time-capacity-sharding-migration-template.sql` | 旧数据迁移模板：创建目标月表、迁移旧分片数据、回填路由元数据和容量元数据。 |

执行顺序：

1. 执行 `alarm-time-capacity-sharding.sql`。
2. 按月份和容量切片复制并调整迁移模板。
3. 分批迁移旧 `alarm_0..4`、`alarm_handle_0..4`、`alarm_electrolytic_cell_0..4`。
4. 校验目标月表、`alarm_shard_route`、`alarm_shard_slice` 行数。
5. 关闭旧 inline 规则，开启 `alarm.sharding.enabled=true`。

## 测试变更

### 自动化测试

| 文件 | 说明 |
| --- | --- |
| `src/test/java/com/hpis/alarm/controller/AlarmTimeCapacityShardingApiTest.java` | 使用 standalone MockMvc 测试接口入参兼容，不启动完整 Spring 容器，不连数据库。 |

覆盖接口：

- `POST /alarm/alarmAdd`
- `POST /alarm/alarmStopByDeviceSn`
- `POST /alarm/alarmStopByIrmsSn`
- `POST /handle`

### 手工 HTTP 用例

| 文件 | 说明 |
| --- | --- |
| `src/test/resources/http/alarm-time-capacity-sharding-api.http` | 手工接口测试脚本，覆盖新增报警、电解槽报警、停止报警、时间范围查询和路由元数据验证。 |

### 测试文档

| 文件 | 说明 |
| --- | --- |
| `doc/alarm-time-capacity-interface-test.md` | 自动化测试、手工接口测试、数据库验证 SQL 和预期结果。 |

## 配置变更

新分片默认关闭：

```yaml
alarm:
  sharding:
    enabled: false
```

启用时使用与 `hpis-quad` 一致的数据源配置方式：

```yaml
spring:
  datasource:
    initialize: false
  shardingsphere:
    enabled: false
    datasource:
      names: ds
      ds:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://...
        username: ...
        password: ...

alarm:
  sharding:
    enabled: true
    maxRowsPerSlice: 5000000
    preCreateMonths: 1
    includeLegacyTables: true
```

启用前必须关闭 Nacos 中旧的 `alarm_id % 5` inline 分片规则，避免两套分片数据源同时生效。

## 接口兼容性

| 接口类型 | 兼容策略 |
| --- | --- |
| 新增报警 | 入口仍是 `insertAlarm(JSONObject)`，旧字段不变；缺少 `time` 时使用当前时间兜底。 |
| 按 `alarm_id` 查询 | 通过 `alarm_shard_route` 精准定位 `table_suffix`。 |
| 按 `alarm_cid` 查询/停止 | 通过 `alarm_shard_route` 精准定位 `table_suffix`。 |
| 按 `device_sn` 停止 | 通过活跃路由元数据定位分片，并同步关闭路由 active 状态。 |
| 按 `irms_sn` 停止 | 通过活跃路由元数据定位分片，并同步关闭路由 active 状态。 |
| 时间范围查询 | 根据 `alarm_beginTime` 推导月份，只路由对应月份和迁移期旧表。 |

## 上线步骤

1. 合入代码，保持 `alarm.sharding.enabled=false`。
2. 在测试库执行基础 DDL。
3. 使用迁移模板迁移一批小范围旧数据。
4. 测试环境关闭旧 inline 规则，并开启新 Java API 分片。
5. 执行自动化接口测试和 HTTP 手工测试。
6. 验证 SQL 日志、目标月表行数、路由元数据。
7. 生产进入维护窗口，执行 DDL 和迁移。
8. 生产切换配置并观察新增报警、查询、停止报警接口。
9. 旧数据全部迁移并验证后，将 `alarm.sharding.includeLegacyTables=false`。

## 回滚方案

未开启新分片时：

- 关闭 `alarm.sharding.enabled` 即可，新增表和新增字段不影响旧 inline 规则。

已开启并产生新月表数据时：

1. 暂停报警写入。
2. 根据 `alarm_shard_route.table_suffix` 找到新月表数据。
3. 按旧规则 `alarm_id % 5` 回写到 `alarm_0..4`、`alarm_handle_0..4`、`alarm_electrolytic_cell_0..4`。
4. 恢复旧 Nacos inline 配置。
5. 关闭 `alarm.sharding.enabled`。

## 已知限制与后续项

| 优先级 | 问题 | 当前处理 |
| --- | --- | --- |
| P3 | 设备停止接口 SQL 排除 `alarm_type = '3'`，但路由元数据按 `device_sn` 批量关闭。 | 已记录在测试和主方案文档，后续应让 `alarm_shard_route.active_flag` 更新条件与业务 SQL 完全一致。 |
| P3 | 旧数据迁移模板按单源表示例编写。 | 生产迁移时需按 `alarm_0..4` 和月份分批复制模板。 |
| P3 | 编译验证受公共模块和本地依赖影响。 | 已记录阻塞原因，待公共模块可编译后重新执行 Maven。 |

## 验证状态

已执行：

```bash
mvn -pl hpis-alarm -Dtest=AlarmTimeCapacityShardingApiTest test
mvn -pl hpis-alarm -DskipTests compile
mvn -pl hpis-alarm -am -DskipTests compile
```

当前阻塞：

- 单模块编译和测试被本地未安装的 `com.hpis:*:2.0.0` 依赖挡住。
- 带 `-am` 的编译被既有公共模块 `hpis-common-core` 中 `sun.management.resources` 引用挡住。

因此当前新增测试和代码已落库，但完整 Maven 验证需要先修复公共模块编译和本地依赖安装问题。

## 相关文档

| 文件 | 说明 |
| --- | --- |
| `doc/alarm-time-capacity-sharding.md` | 分片方案主说明。 |
| `doc/alarm-time-capacity-table-change.md` | 表结构和 SQL 变更说明。 |
| `doc/alarm-time-capacity-interface-test.md` | 接口测试说明。 |
| `doc/alarm-time-capacity-change-record.md` | 本次整体修改记录。 |
