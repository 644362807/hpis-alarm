# 报警ID与容量预占设计说明

## 背景

早期压测发现，如果 `alarm_id` 只由报警业务时间和本地序列生成，同一批历史时间数据重复压测或服务重启后可能复用旧 ID，导致主键冲突。

当前方案保持 `alarm_id` 为 `bigint Long`，但改为可解析路由型 ID，并把唯一性主要交给数据库 Segment 预占的 `rowNo` 保证。

## alarm_id v2 布局

63 位正数布局：

```text
dayOffset(15) + sliceNo(8) + workerId(8) + snowflakeSeed(9) + rowNo(23)
```

字段含义：

- `dayOffset`：报警开始日期相对 epoch 的天数，默认 epoch 为 `2020-01-01`。
- `sliceNo`：月内 slice 序号，用于直达 `alarm_yyyyMM_nn`。
- `workerId`：实例编号，来自 Nacos `alarm.sharding.id.workerId`，默认 `0`。
- `snowflakeSeed`：从现有 Snowflake 取 9 位扰动，降低同批局部碰撞概率。
- `rowNo`：当前 slice 内全局预占行号，范围 `0..8388607`。

## Segment 预占流程

1. start 链路解析 `alarm_beginTime`。
2. `AlarmMonthlySliceTableManager.allocate` 根据月份查找当前 slice。
3. 本机内存中如果已有可用 Segment，直接取下一个 `rowNo`，不访问数据库。
4. 本机 Segment 用完后，进入数据库短事务：
   - 锁定当前月份最新 slice。
   - 按 `alarm.sharding.allocationSegmentSize` 预占一段 rowNo。
   - 更新 `alarm_shard_slice.current_rows`。
5. 如果当前 slice 剩余容量不足，创建下一个 `yyyyMM_nn` slice，并从新 slice 预占。
6. 根据 `alarm_beginTime + sliceNo + workerId + snowflakeSeed + rowNo` 生成内部 `alarm_id`。

## current_rows 语义

`alarm_shard_slice.current_rows` 表示“已预占行数”，不是精确真实行数。

允许出现空洞：

- 服务拿到 Segment 后宕机，未用完的 rowNo 不回收。
- 部分业务事务回滚，已分配 rowNo 不回收。

这种空洞不影响：

- `alarm_id` 唯一性。
- 按 ID 解析分片。
- 容量上限保护。

## 为什么采用 Segment

| 方案 | 优点 | 缺点 | 本阶段选择 |
| --- | --- | --- | --- |
| 纯 Snowflake | 吞吐高 | 不能按报警业务时间直接路由，历史时间复跑仍有风险 | 不采用 |
| DB/Leaf Segment | 可重启、可复跑、分布式安全，DB 访问从逐条降为按段 | 允许预占空洞，依赖 DB 短事务 | 采用 |
| Redis INCR | 性能好 | 引入 Redis 强一致依赖和补偿问题 | 不采用 |
| UUID/String | 唯一性强 | 改字段和接口，不兼容现有 bigint | 不采用 |

## 配置要求

```yaml
alarm:
  sharding:
    maxRowsPerSlice: 5000000
    allocationSegmentSize: 1000
    id:
      workerId: 0
```

约束：

- `maxRowsPerSlice` 不能超过 `rowNo` 23 位上限。
- 多实例部署时每个实例必须配置不同 `workerId`。
- 不允许在保留业务分片表数据的情况下清空 `alarm_shard_slice`，否则可能重新分配旧 rowNo。

## 优势与短板

优势：

- 同一批历史时间重复压测不再复用旧 ID。
- 服务重启后从 DB 继续预占，不依赖本地内存恢复。
- 分片分配不再每条报警都 `synchronized + select for update + tableExists`。

短板：

- `current_rows` 不是精确真实行数。
- Segment 过大时空洞可能较多；过小时 DB 预占频率升高。
- 该方案只解决 ID 和容量分配，不直接解决业务多表写入成本。
