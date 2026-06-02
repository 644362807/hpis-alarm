# 本轮修改记录：stop worker PROCESSING 认领与专用线程池

## 背景

旧 stop worker 在一次 scheduled 调度中串行执行多批关闭。积压达到几十万条时，单次任务持续时间过长；`countPending`、route 查询、物理表关闭、route 关闭、`markApplied` 和 side effect upsert 串在同一执行节奏中，消费速度难以追上 stop 写入速度。

## 本轮实现

- `alarm_stop_event` 新增 `PROCESSING`、`lock_token`、`locked_at`、`available_time`。
- 新增 `AlarmStopEventClaimService`：短事务原子认领、失败释放、超时回收。
- 新增 stop 专用 `ThreadPoolTaskExecutor`，默认 `4 x 200`。
- 原有通用线程池保持 `@Primary`，避免 WebSocket 等旧代码按类型注入时被新增专用池影响。
- `AlarmStopEventWorker` 只做派发，不在 scheduled 线程内跑长循环。
- 核心关闭使用 `PROCESSING + lock_token + id` 条件提交 `APPLIED/PENDING/FAILED`。
- route 暂不可见时按 `available_time` 延迟重试，默认 `5000ms`。
- 历史 `FAILED/ROUTE_MISSING` 由独立低频任务恢复，避免替换旧 worker 后丢失恢复入口。
- 物理 alarm 表批量关闭后校验实际更新行数，再关闭 route。
- side effect 在仍有 `PENDING/PROCESSING` 时暂停，优先保护核心闭环。
- verifier 默认每 `5` 轮做一次完整分片 count，终态门禁新增 `PROCESSING=0`。
- outstanding 热探测使用 `select 1 ... limit 1`，兼容 ShardingSphere 结果映射且避免全量 count。

## 数据库迁移

新环境通过 `alarm-time-capacity-sharding.sql` 创建完整结构。已有环境必须在低峰期先执行：

```text
src/main/resources/sql/alarm-stop-event-processing-claim-migration.sql
```

应用启动阶段不会自动对大表执行 `ALTER TABLE`。灰度前需要检查两个新索引存在：

- `idx_stop_event_claim(event_status, available_time, created_time, id)`
- `idx_stop_event_processing_timeout(event_status, locked_at)`

## 配置与回退

- 正常灰度：`dispatchEnabled=true`、`workerThreads=4`、`claimBatchSize=200`、`maxInFlightBatches=4`。
- 暂停新认领：`dispatchEnabled=false`。MQ stop 仍继续可靠写入 `alarm_stop_event`。
- 降低 DB 压力：`workerThreads=1`、`claimBatchSize=100`。
- 吞吐不足：只按 `4 -> 6 -> 8` 增加线程数，不能直接放大 SQL 批次。

## 已完成验证

- Java 8 + UTF-8，从父工程执行 hpis-alarm focused 测试。
- 单测覆盖 token 条件提交、route missing 延迟重试、不同认领 token、异常释放、超时回收、重复 stop 不重置 `PROCESSING` XML 契约。

## 待执行门禁

- 本地 MySQL 执行迁移脚本后，重启 hpis-alarm。
- 按 `10000 -> 50000 -> 100000 -> 750000 PENDING` 跑真实 RabbitMQ + MySQL。
- 75 万积压 30 分钟内完成，平均吞吐 `>= 417 rows/s`。
- 最终 `PENDING=0`、`PROCESSING=0`、`FAILED=0`，队列 ready/unacked 为 `0`，主表、扩展表、route suffix 和 alarmId 一致。

## 并发认领补充

- `GENERAL_ALTERNATE 10000` 首轮真实回归最终闭环通过，但日志发现多个线程并发执行
  `UPDATE ... ORDER BY ... LIMIT` 认领时存在 MySQL next-key 锁竞争并触发 deadlock。
- 单实例内已将认领短事务改为串行临界区，并使用 `READ_COMMITTED` 降低范围锁冲突。
  临界区只覆盖认领更新和 token 查询，已认领批次的物理表关闭、route 关闭和 `APPLIED`
  提交仍由专用线程池并行执行。
- claim 遇到 deadlock 等瞬时数据库冲突时，最多退避重试 `3` 次；超过上限才记录失败并等待下轮派发。
- 超时回收、历史 `ROUTE_MISSING` 恢复和 APPLIED 清理在存在 in-flight 核心批次时跳过，
  避免维护 SQL 与热路径争抢 `alarm_stop_event` 锁。
- 多实例部署仍需要在灰度阶段验证数据库锁竞争；如需同时开启多个实例，优先采用单活 dispatcher
  或数据库级协调，不通过放大 SQL 批次规避锁竞争。

## Mixed 回归补充

- `MIXED_ALTERNATE 10000` 回归暴露电解槽 start 批量写入的历史锁竞争：
  `deleteOldAlarmEctypeByItems` 使用多组 `OR` 删除当前点位旧记录，本机表又缺少点位索引。
- 第一次补充普通点位索引后，`MIXED_ALTERNATE 10000` 仍出现重叠点位范围删除 deadlock。
- 电解槽当前点位写入进一步改为批内去重、固定点位顺序、按 `500` 切块原子 upsert；配套
  `uk_ec_ectype_point` 唯一点位索引，并移除已经不再使用的删旧 Mapper。
- 第二次 mixed 回归的 InnoDB deadlock 快照显示冲突转移到 `PRIMARY(alarm_id)` 间隙锁。当前点位表的
  `alarm_id` 会随最新报警变化，不适合作为物理主键；迁移脚本已改为稳定自增 `ectype_id` 主键，
  并保留普通 `idx_ec_ectype_alarm_id` 与点位唯一 `uk_ec_ectype_point`。
- 第三次 mixed 回归显示额外的 `uk_ec_ectype_alarm_id` 仍会对持续变化的 alarmId 争抢间隙锁。该字段在当前点位快照表
  只用于按报警删除和定位，唯一性由报警主链路保证，因此迁移脚本最终使用普通 `idx_ec_ectype_alarm_id`。
- 第四次 mixed 回归仍在自增主键尾部 `supremum` 位置出现 insert intention deadlock。第二阶段停止放大，
  阻断结果、测试数据和后续方案见 `报警Stop闭环第二阶段压测阻断报告-20260531.md`。
- 已有环境还必须在低峰期执行：

```text
src/main/resources/sql/alarm-electrolytic-cell-ectype-point-index-migration.sql
```

- 本机还发现环境 schema 漂移：仓库代码使用 `alarm_device_configure.device_sn`，本机旧表仍为
  `device_id`；仓库代码使用 `alarm_configure.device_sn`，本机旧表仍为 `device_serial_number`。
  该问题已存在于 `main`，需要在部署前核对正式库 DDL，不在 stop-worker 分支猜测式改表。
