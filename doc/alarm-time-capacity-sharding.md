# hpis-alarm 时间 + 容量分片改造说明

## 背景

原规则按 `alarm_id % 5` 固定分到 `alarm_0..4`、`alarm_handle_0..4`、`alarm_electrolytic_cell_0..4`。这种规则无法按时间清理或归档，也无法在单月数据突增时继续扩展子表。

本次改造仅修改 `hpis-alarm`，保留 ShardingSphere 4.1.1，参考 `hpis-quad` 的 4.1.x Java API 动态建表思路，不直接改 `hpis-quad`。

## 应用评估

该方案可以应用到现有 `hpis-alarm`，但建议按“先合代码、后切配置、再迁移数据”的方式灰度上线。代码默认 `alarm.sharding.enabled=false`，不主动替换现有分片数据源；只有 Nacos 开启该开关并关闭旧 inline 规则后，才会使用新的 Java API 分片规则。

对原接口的影响控制在服务内部：

- Controller 入参和返回结构不变。
- 新增报警仍由 `insertAlarm` 入口写入，缺少 `time` 时会用当前时间兜底，避免旧上报无法路由。
- 按 `alarm_id`、`alarm_cid`、`device_sn`、`irms_sn` 的查询和停止接口通过 `alarm_shard_route` 定位表，不要求调用方传表后缀。
- 上线前必须给 `alarm_handle` 和 `alarm_electrolytic_cell` 补 `alarm_beginTime` 字段；否则新 Mapper 写入该字段时会因数据库列不存在而失败。

## 新规则

物理表后缀为 `yyyyMM_nn`，例如：

- `alarm_202604_00`
- `alarm_handle_202604_00`
- `alarm_electrolytic_cell_202604_00`

写入时先按 `alarm_beginTime` 定位月份，再按 `alarm.sharding.maxRowsPerSlice` 判断是否切到下一个月内子表。默认阈值为 `5000000`。

## 兼容策略

现有 Controller 入参和返回结构不变。旧接口按 `alarm_id`、`alarm_cid`、`device_sn`、`irms_sn` 查询或停止报警时，通过 `alarm_shard_route` 找到 `table_suffix`，避免调用方感知新表名。

写入时通过 `AlarmShardContext` 保存本次报警的 `table_suffix`，保证 `alarm`、`alarm_handle`、`alarm_electrolytic_cell` 三张绑定表落到同一个物理分片。

迁移期 `alarm.sharding.includeLegacyTables=true` 会让兜底路由带上旧 `alarm_0..4`，旧数据迁移并回填元数据后应改为 `false`。

## 配置示例

```yaml
alarm:
  sharding:
    enabled: true
    maxRowsPerSlice: 5000000
    preCreateMonths: 1
    includeLegacyTables: true

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
        url: jdbc:mysql://127.0.0.1:3306/hpis?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
        username: root
        password: root
```

新 Java API 分片数据源沿用 `hpis-quad` 的 `spring.shardingsphere.datasource.ds` 配置方式，并通过 `spring.shardingsphere.enabled=false` 禁用 ShardingSphere Starter 自动配置。启用前必须关闭 Nacos 中旧的 `alarm_id % 5` inline 分片规则，避免两套分片数据源同时生效。

## 数据库变更

表结构说明：

`doc/alarm-time-capacity-table-change.md`

整体修改记录：

`doc/alarm-time-capacity-change-record.md`

SQL 文件：

- `src/main/resources/sql/alarm-time-capacity-sharding.sql`
- `src/main/resources/sql/alarm-time-capacity-sharding-migration-template.sql`

接口测试说明：

`doc/alarm-time-capacity-interface-test.md`

必须新增：

- `alarm_shard_slice`
- `alarm_shard_route`
- `alarm_handle.alarm_beginTime`
- `alarm_electrolytic_cell.alarm_beginTime`

## 迁移步骤

1. 停止写入或进入短暂维护窗口。
2. 执行元数据表和新增字段 DDL。
3. 按旧 `alarm_0..4` 的 `alarm_beginTime` 计算目标 `yyyyMM_nn`，把 `alarm`、`alarm_handle`、`alarm_electrolytic_cell` 迁移到同一 suffix。
4. 回填 `alarm_shard_route`，并按每个月每个 suffix 的行数回填 `alarm_shard_slice.current_rows`。
5. Nacos 关闭旧 inline 规则，开启 `alarm.sharding.enabled=true`。
6. 验证按 ID、按外部告警 ID、按设备停止、按时间范围查询的 SQL 路由。
7. 迁移稳定后设置 `alarm.sharding.includeLegacyTables=false`。

## 回滚

关闭 `alarm.sharding.enabled`，恢复旧 Nacos inline 配置。若已产生新月表数据，需要按 `alarm_shard_route.table_suffix` 把新数据回写到旧 `alarm_id % 5` 对应表。

## 验收清单

- 新增报警后三张绑定表 suffix 一致。
- 单月阈值调小后，`yyyyMM_00` 满后自动切到 `yyyyMM_01`。
- `/alarm/list` 时间范围只命中对应月份和迁移期旧表。
- 按 `alarm_id`、`alarm_cid` 查询不扫描全部历史月表。
- 设备停止和网关停止后 `alarm_shard_route.active_flag` 更新为 `0`。

## 后续待处理

- 设备停止接口当前业务 SQL 会排除 `alarm_type = '3'`，但路由元数据按 `device_sn` 批量关闭。该问题先记录为后续优化项，后续应把 `alarm_shard_route.active_flag` 的更新条件和实际停止 SQL 保持一致。
