# hpis-alarm 时间 + 容量分片接口测试说明

## 测试目标

验证 alarm 时间 + 容量分片改造后，旧接口参数和返回结构保持兼容，同时关键分片字段可以正确进入业务链路。

重点覆盖：

- 新增报警接口仍接收旧的 `alarmId`、`deviceSn`、`irmsSn`、`time`。
- `time` 可以作为 `alarm_beginTime` 的来源，进入后续时间分片。
- 设备停止、IRMS 停止接口参数不变，并能用于更新路由元数据。
- `alarm_handle` 直接新增时可以接收 `alarmBegintime`，对应数据库字段 `alarm_beginTime`。
- 时间范围查询类接口应带真实时间条件，避免无谓扫描全部历史分片。

## 自动化接口契约测试

测试类：

`src/test/java/com/hpis/alarm/controller/AlarmTimeCapacityShardingApiTest.java`

该测试使用 `MockMvcBuilders.standaloneSetup`，不启动完整 Spring 容器，不连接数据库，只验证 Controller 接口入参绑定和 Service 透传。

覆盖用例：

| 用例 | 接口 | 验证点 |
| --- | --- | --- |
| `alarmAddKeepsLegacyKeysAndTimeForShardRoute` | `POST /alarm/alarmAdd` | `alarmId`、`time`、`deviceSn`、`irmsSn`、`sceneType` 原样传给 `IAlarmService.insertAlarm`。 |
| `alarmStopByDeviceSnKeepsDeviceAndStopTimeForRouteMetadata` | `POST /alarm/alarmStopByDeviceSn` | `deviceSn`、`time` 原样传给停止接口。 |
| `alarmStopByIrmsSnKeepsIrmsAndStopTimeForRouteMetadata` | `POST /alarm/alarmStopByIrmsSn` | `irmsSn`、`time` 原样传给停止接口。 |
| `alarmHandleAddAcceptsAlarmBeginTimeForBoundTableRouting` | `POST /handle` | JSON 中 `alarmBegintime` 能绑定为 `AlarmHandle.alarmBegintime`。 |

执行命令：

```bash
mvn -pl hpis-alarm -Dtest=AlarmTimeCapacityShardingApiTest test
```

如果本地没有安装 `com.hpis:*:2.0.0` 依赖，请先修复公共模块编译问题并执行：

```bash
mvn -pl hpis-alarm -am -DskipTests install
```

## 手工接口测试

HTTP 用例文件：

`src/test/resources/http/alarm-time-capacity-sharding-api.http`

执行前置条件：

1. 执行 `src/main/resources/sql/alarm-time-capacity-sharding.sql`。
2. 如需验证旧数据，按 `src/main/resources/sql/alarm-time-capacity-sharding-migration-template.sql` 迁移一批旧数据。
3. Nacos 使用 hpis-quad 同款 `spring.shardingsphere.datasource.ds` 数据源配置。
4. 关闭旧 `alarm_id % 5` inline 规则。
5. 开启 `alarm.sharding.enabled=true`。
6. 打开 SQL 日志，便于确认实际命中的物理表。

## 数据库验证 SQL

新增报警后验证路由元数据：

```sql
SELECT alarm_id, alarm_cid, device_sn, irms_sn, alarm_beginTime, table_suffix, active_flag
FROM alarm_shard_route
WHERE alarm_cid IN ('cid-202604-001', 'cid-202604-ec-001');
```

验证同一条电解槽报警三张绑定表 suffix 一致：

```sql
SELECT 'alarm' AS table_name, alarm_id FROM alarm_202604_00 WHERE alarm_cid = 'cid-202604-ec-001'
UNION ALL
SELECT 'alarm_handle' AS table_name, alarm_id FROM alarm_handle_202604_00
WHERE alarm_id IN (SELECT alarm_id FROM alarm_202604_00 WHERE alarm_cid = 'cid-202604-ec-001')
UNION ALL
SELECT 'alarm_electrolytic_cell' AS table_name, alarm_id FROM alarm_electrolytic_cell_202604_00
WHERE alarm_id IN (SELECT alarm_id FROM alarm_202604_00 WHERE alarm_cid = 'cid-202604-ec-001');
```

验证容量切片元数据：

```sql
SELECT month_key, slice_no, table_suffix, current_rows, max_rows
FROM alarm_shard_slice
WHERE month_key = '202604'
ORDER BY slice_no;
```

设备停止或 IRMS 停止后验证活跃状态：

```sql
SELECT alarm_id, alarm_cid, device_sn, irms_sn, table_suffix, active_flag
FROM alarm_shard_route
WHERE device_sn = 'device-001' OR irms_sn = 'irms-001';
```

## 预期结果

- 新增报警后 `alarm_shard_route.table_suffix` 为 `yyyyMM_nn` 格式。
- 电解槽报警的 `alarm`、`alarm_handle`、`alarm_electrolytic_cell` 命中同一个后缀。
- 当 `alarm.sharding.maxRowsPerSlice` 调小后，同月 `00` 子表满后自动创建并切到 `01`。
- 按 `alarm_id`、`alarm_cid` 查询时优先通过 `alarm_shard_route` 精准路由。
- 时间范围查询只命中对应月份的新月表，以及迁移期配置允许的旧 `alarm_0..4`。

## 注意事项

- `POST /alarm/alarmAdd` 当前 Controller 捕获异常后只打印日志，不向调用方抛出错误；接口测试需要结合服务日志和数据库结果判断是否真正落库。
- `POST /alarm/alarmStopByDeviceSn` 的 `alarm_type = '3'` 路由元数据一致性问题已记录为后续优化项。
- 手工接口测试中的 token、host、租户和设备 SN 需要替换成测试环境真实数据。
