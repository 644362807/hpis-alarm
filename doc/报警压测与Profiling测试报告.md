# 报警压测与 Profiling 测试报告

## 测试目标

- 验证 stop worker 批量化后 2 万开始报警 + 2 万结束报警可以完整闭环。
- 验证一般行业不会误触发 `EC_ECTYPE_DELETE`。
- 验证电解槽行业可以生成 `EC_ECTYPE_DELETE`。
- 通过 `ALARM_PROFILE` 确认逐条 SQL 是否已从主要耗时中移除。

## 测试环境

- 服务：本机 `hpis-alarm`。
- 启动 JDK：`C:\Program Files\Java\jdk1.8.0_321`。
- MQ：`127.0.0.1:5672`，队列 `alarm_queue`。
- 数据库：`hpis_alarm`。
- 目标月份：`202511`。
- 目标物理表：`alarm_202511_00`。

注意：默认 `java` 为 JDK 21 时，ShardingSphere/MyBatis 在插入 `alarm_handle` 时触发 `InaccessibleObjectException`，导致消息 requeue、业务表 0 行。正式压测已改用 JDK 8。

## 小流量回归

| 场景 | RunId | 结果 | 关键数据 |
| --- | --- | --- | --- |
| 一般行业 1000 start + 1000 stop | `batch-small-general-20260514-002` | 通过 | `alarmRows=1000`、`closedRows=1000`、`APPLIED=1000`、`EC_ECTYPE_DELETE=0`、`IR_OFFLINE_RECOVER=43` |
| 电解槽 1000 start + 1000 stop | `batch-small-electro-20260514-002` | 通过 | `alarmRows=1000`、`closedRows=1000`、`APPLIED=1000`、`EC_ECTYPE_DELETE=1000`、`IR_OFFLINE_RECOVER=53` |

## 大数据量压测

| 场景 | RunId | 发送耗时 | 闭环验证耗时 | 最终状态 |
| --- | --- | ---: | ---: | --- |
| 一般行业 95% 温度 + 5% 断线 | `batch-general-20k-20260514-001` | 20.80s | 281.28s | `alarmRows=20000`、`closedRows=20000`、`APPLIED=20000`、`queueReady=0`、`IR_OFFLINE_RECOVER=1004` |
| 电解槽 95% 温度 + 5% 断线 | `batch-electro-20k-20260514-001` | 20.66s | 361.42s | `alarmRows=20000`、`closedRows=20000`、`APPLIED=20000`、`queueReady=0`、`EC_ECTYPE_DELETE=20000`、`IR_OFFLINE_RECOVER=992` |

## Profiling 结果

### 一般行业 2 万

| 指标 | 次数 | 总耗时 |
| --- | ---: | ---: |
| MQ listener 总耗时 | 40000 | 281943ms |
| start 分支 | 20000 | 176977ms |
| `insertAlarm` | 20000 | 110750ms |
| stop 分支 | 20000 | 94025ms |
| stop event upsert | 20000 | 93103ms |
| stop worker 批处理 | 309 | 48483ms |
| active route 查询聚合 | 20000 | 40124ms |

批量化指标：

| 指标 | 次数 | 总耗时 |
| --- | ---: | ---: |
| `closeHotBatch` | 52 | 1277ms |
| `markAppliedBatch` | 52 | 691ms |
| `batchStopByAlarmIds` | 52 | 677ms |
| `markDoneBatch` | 52 | 408ms |

### 电解槽 2 万

| 指标 | 次数 | 总耗时 |
| --- | ---: | ---: |
| MQ listener 总耗时 | 40000 | 341309ms |
| start 分支 | 20000 | 229811ms |
| `insertAlarm` | 20000 | 164259ms |
| stop 分支 | 20000 | 99100ms |
| stop event upsert | 20000 | 98117ms |
| 电解槽扩展插入 | 19008 | 61631ms |
| stop worker 批处理 | 884 | 82116ms |
| 创建 side effect event | 20000 | 43562ms |
| 执行 `EC_ECTYPE_DELETE` | 20000 | 39505ms |

批量化指标：

| 指标 | 次数 | 总耗时 |
| --- | ---: | ---: |
| `closeHotBatch` | 38 | 1267ms |
| `markAppliedBatch` | 38 | 671ms |
| `batchStopByAlarmIds` | 38 | 843ms |
| `markDoneBatch` | 43 | 1136ms |

## 结论

- 本轮目标中的逐条 `closeRoute`、`markApplied`、`side effect markDone` 已从 2 万次降到几十批。
- 当前 2 万闭环仍需 4 到 6 分钟，主因已经转移到 MQ listener 消费、start 插入链路、stop event upsert 和电解槽 side effect。
- 一般行业未出现 `EC_ECTYPE_DELETE`。
- 电解槽行业 `EC_ECTYPE_DELETE=20000`，符合预期。
- side effect 可延迟，不阻塞核心 `alarm_endTime` 闭环。

## 后续建议

1. 优先调整 RabbitMQ listener 并发、prefetch 和消费线程配置。
2. start 插入链路继续拆非核心同步逻辑，尤其电解槽扩展表写入。
3. 如 stop event upsert 仍是瓶颈，再考虑 MQ 批量消费和批量 upsert。
4. 电解槽 `EC_ECTYPE_DELETE` 可改为按 alarmId 批量执行，减少 side effect worker DB 往返。
