# hpis-alarm 表结构与 SQL 变更说明

## 变更范围

本次表结构变更只覆盖 `hpis-alarm` 的报警分片相关表。`hpis-quad` 只作为 Java API 动态表思路参考，不直接修改。

## 新增表

| 表名 | 类型 | 作用 |
| --- | --- | --- |
| `alarm_shard_slice` | 元数据表 | 记录每个月下有哪些容量子表、当前行数和行数阈值，用于判断 `alarm_yyyyMM_00` 满后是否切到 `alarm_yyyyMM_01`。 |
| `alarm_shard_route` | 元数据表 | 记录 `alarm_id`、`alarm_cid`、`device_sn`、`irms_sn` 到 `table_suffix` 的映射，保证旧接口不传分片后缀也能精准路由。 |

### alarm_shard_slice

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `bigint` | 自增主键 |
| `month_key` | `varchar(6)` | 月份，格式 `yyyyMM` |
| `slice_no` | `int` | 月内子表序号，从 `0` 开始 |
| `table_suffix` | `varchar(16)` | 物理表后缀，例如 `202604_00` |
| `current_rows` | `bigint` | 当前路由计数 |
| `max_rows` | `bigint` | 单表行数阈值 |
| `create_time` | `datetime` | 创建时间 |
| `update_time` | `datetime` | 更新时间 |

关键约束：

- `uk_alarm_shard_slice_month_no (month_key, slice_no)`
- `uk_alarm_shard_slice_suffix (table_suffix)`

### alarm_shard_route

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `alarm_id` | `bigint` | 报警主键 |
| `alarm_cid` | `varchar(128)` | 外部告警 ID |
| `device_sn` | `varchar(255)` | 设备 SN |
| `irms_sn` | `varchar(128)` | 网关/IRMS SN |
| `alarm_beginTime` | `datetime` | 报警开始时间 |
| `table_suffix` | `varchar(16)` | 物理表后缀，例如 `202604_00` |
| `active_flag` | `tinyint` | 是否仍为活跃报警 |
| `create_time` | `datetime` | 创建时间 |
| `update_time` | `datetime` | 更新时间 |

关键索引：

- `PRIMARY KEY (alarm_id)`
- `idx_alarm_shard_route_cid (alarm_cid)`
- `idx_alarm_shard_route_device (device_sn, active_flag)`
- `idx_alarm_shard_route_irms (irms_sn, active_flag)`
- `idx_alarm_shard_route_time (alarm_beginTime)`
- `idx_alarm_shard_route_suffix (table_suffix)`

## 修改表

| 表名 | 修改内容 | 原因 |
| --- | --- | --- |
| `alarm` | 不新增字段，复用已有 `alarm_beginTime` | 主表已有时间字段，可直接作为时间分片键。 |
| `alarm_handle` | 新增 `alarm_beginTime datetime DEFAULT NULL` | 处理表需要和主表使用同一个时间分片键，避免绑定表跨分片。 |
| `alarm_electrolytic_cell` | 新增 `alarm_beginTime datetime DEFAULT NULL` | 电解槽关联表需要和主表使用同一个时间分片键，避免绑定表跨分片。 |
| `alarm_handle_0..4` | 新增 `alarm_beginTime datetime DEFAULT NULL` | 旧分片表迁移前需要补列，保证旧数据可回填到新月表。 |
| `alarm_electrolytic_cell_0..4` | 新增 `alarm_beginTime datetime DEFAULT NULL` | 旧分片表迁移前需要补列，保证旧数据可回填到新月表。 |

## 动态物理表

以下物理表由 `AlarmMonthlySliceTableManager` 按需创建，不建议手工长期维护：

| 逻辑表 | 新物理表格式 | 示例 |
| --- | --- | --- |
| `alarm` | `alarm_yyyyMM_nn` | `alarm_202604_00` |
| `alarm_handle` | `alarm_handle_yyyyMM_nn` | `alarm_handle_202604_00` |
| `alarm_electrolytic_cell` | `alarm_electrolytic_cell_yyyyMM_nn` | `alarm_electrolytic_cell_202604_00` |

建表方式使用 `CREATE TABLE ... LIKE` 复用现有模板表结构。新月表的创建由代码触发：

- 服务启动时预建当前月和未来 `alarm.sharding.preCreateMonths` 个月的 `00` 子表。
- 写入时如果当前月当前子表达到 `alarm.sharding.maxRowsPerSlice`，自动创建下一个 `nn` 子表。

## SQL 文件

| 文件 | 作用 |
| --- | --- |
| `src/main/resources/sql/alarm-time-capacity-sharding.sql` | 基础 DDL：创建元数据表，并幂等补齐 `alarm_beginTime` 字段。 |
| `src/main/resources/sql/alarm-time-capacity-sharding-migration-template.sql` | 旧数据迁移模板：创建目标月表、迁移旧表数据、回填 `alarm_shard_route` 和 `alarm_shard_slice`。 |

## 执行顺序

1. 执行 `alarm-time-capacity-sharding.sql`。
2. 选择迁移月份和容量切片，复制并调整 `alarm-time-capacity-sharding-migration-template.sql`。
3. 对旧 `alarm_0..4`、`alarm_handle_0..4`、`alarm_electrolytic_cell_0..4` 分批迁移。
4. 验证目标月表、路由表、容量表行数。
5. Nacos 关闭旧 `alarm_id % 5` inline 规则。
6. 使用 hpis-quad 同款 `spring.shardingsphere.datasource.ds` 配置，并开启 `alarm.sharding.enabled=true`。

## 回滚说明

未切换配置前，新增表和新增字段不会改变旧路由规则。若已切到新分片并产生新数据，回滚前需要根据 `alarm_shard_route.table_suffix` 找到新月表数据，再按旧 `alarm_id % 5` 写回 `alarm_0..4`。
