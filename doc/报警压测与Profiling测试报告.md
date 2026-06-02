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

## MQ start start-only 1000 条对比

本节用于对比三种 MQ start 消费方案。统计口径以应用日志中 `alarm insert stage=PERSIST, result=SUCCESS` 的首尾时间为主，避免 Maven 编译、测试启动和 verifier 启动时间污染吞吐判断。

| 场景 | RunId | 配置 | 结果 | 实际批量 | PERSIST 首尾耗时 | 估算吞吐 |
| --- | --- | --- | --- | --- | ---: | ---: |
| 旧单条 listener | `BASE1000-20260521224452` | 旧 `RabbitMQAlarmListener`，`concurrency=10-36` | 通过，`alarmRows=1000`、`queueReady=0`、`ACTIVE=1000` | 单条 | 9595ms | 104.22/s |
| 历史 submitAndWait 聚合（已移除） | `AGG1000-20260521223652` | `insertAggregateEnabled=true`、`triggerMode=always` | 通过，`alarmRows=1000`、`queueReady=0`、`ACTIVE=1000` | 100 次 flush，每次约 10 条 | 25799ms | 38.76/s |
| Spring AMQP consumer batch | `BATCH1000-20260522224039` | `insertConsumerBatchEnabled=true`、`batchSize=100`、`receiveTimeoutMs=50`、`concurrency=2-4`、`prefetch=100` | 通过，`alarmRows=1000`、`queueReady=0`、`ACTIVE=1000`、`nack=0` | 10 批，每批 100 条 | 2723ms | 367.24/s |

补充观测：

- `BATCH1000-20260522224039` 的 RabbitMQ 队列最终 `messages=0`、`messages_ready=0`、`messages_unacknowledged=0`，启动时消费者数为 2。
- batch listener 日志显示 `BATCH_CONSUME_DONE` 共 10 次，`actualBatchSize=100` 共 10 次，`ackCount=1000`、`nackCount=0`。
- consumer batch 相对旧单条 listener 约提升 3.5 倍；相对旧 `submitAndWait` 聚合约提升 9.5 倍。
- 旧 `submitAndWait` 聚合虽然功能通过，但 listener 线程同步等待导致实际 batch 被消费者并发数限制，不能作为第一阶段吞吐优化主方案。
- 2026-05-23 已清理旧聚合器代码和配置入口，生产只推荐 `insertConsumerBatchEnabled=true` 的 Spring AMQP consumer batch。

## MQ start consumer batch 待验证项

本节是后续上线前仍建议补齐的压测清单。`BATCH1000-20260522224039` 已验证 start-only 1000 条正常运行和批量吞吐收益，但还没有覆盖混合消息、异常和停机场景。

| 场景 | 目标 | 验收点 |
| --- | --- | --- |
| 低流量延迟 | 验证 `insertConsumerBatchReceiveTimeoutMs=50` 对低流量 start 的额外等待是否可接受 | P95/P99 listener 耗时、ack 延迟不超过业务阈值 |
| consumer batch 100/200 条吞吐 | 验证主表、处理表、扩展表、cid route 的批量写收益 | 同等消息量下 SQL 次数下降，事务耗时稳定，无连接池打满 |
| 混合 start/stop 同批 | 验证同一批内先处理 start 后处理 stop/status | start batch 调用先于 `recordStop`，不会因 route 尚未写入而扩大 `ROUTE_MISSING` 误判 |
| 批量失败拆单 | 人工制造某个 suffix/mapper 批量失败 | 批量事务回滚，逐条 `persistPreparedAlarmSingle(context)`，不重新执行 Redis 去重，成功部分 ack，失败部分 nack/requeue |
| 设备缓存缺失 | 混入无法解析设备缓存的 start 消息 | 当前 item `DROP/ack`，同批其他 item 正常入库 |
| push 兼容 | 对比 batch 前后 push payload | 字段、结构、afterCommit 时机保持旧消费者 100% 兼容 |

## 后续建议

1. 第一优先级改为灰度 `alarm.batch.insertConsumerBatchEnabled=true`，从 `batchSize=100`、`receiveTimeoutMs=50`、`concurrency=2-4`、`prefetch=100` 起步。
2. 旧 `AlarmInsertAggregateService.submitAndWait` 已清理，不再作为灰度入口；配置中心残留 `insertAggregate*` 应同步删除。
3. 灰度时临时打开 `insertConsumerBatchLogEnabled=true` 观察 `actualBatchSize`、`ackCount`、`nackCount`，稳定后关闭普通成功日志。
4. start 插入链路继续拆非核心同步逻辑，尤其电解槽扩展表写入。
5. 如 stop event upsert 仍是瓶颈，再考虑 MQ 批量消费和批量 upsert。
6. 电解槽 `EC_ECTYPE_DELETE` 可改为按 alarmId 批量执行，减少 side effect worker DB 往返。

## 2026-05-24 压测计时口径修正

`LT-GST-2K-20260524174500` 报告中 `Verify elapsed=1039ms`，但 `Consume elapsed after send=48611ms`，说明 verifier 在发送完成约 47 秒后才开始采样；该轮闭环耗时混入了人工/脚本/Maven 启动空窗，不能用于证明 2000 档真实消费比更大数据量更慢。`LT-GALT-2K-20260524181157-4` 还受到 Windows class 文件锁后手动改直跑 verifier 的影响，同样不采纳性能结论。

本轮新增 `AlarmMqLoadOrchestratorMain`，先启动 verifier 采样，再发送 MQ。后续报告新增以下字段：

- `Verifier startup gap ms`：send 完成到 verifier 启动的空窗。
- `First observed elapsed after send ms`：发送完成后首次采样时间。
- `Queue drain elapsed after send ms`：队列 ready 首次为 0 的时间。
- `DB closed elapsed after send ms`：`closedRows/APPLIED/PENDING/FAILED` 首次满足核心验收的时间。
- `True closed-loop elapsed ms`：仅当 verifier 启动空窗不超过采样间隔时采纳，否则记为 `-1`。

因此后续 `2000 / 10000 / 50000 / 100000` 性能对比只采纳 orchestrator 生成、`True closed-loop elapsed ms != -1` 且最终 PASS 的 run。当前 `2-4` consumer 下已通过的 start-then-stop 大档闭环约 `295-337 rows/s`，瓶颈主要在 stop worker 与 MySQL route/业务表批量关闭，不建议在修复 `ROUTE_MISSING` 前盲目扩大 MQ consumer 并发。

## 2026-05-30 SQL 批处理硬边界

本轮增加统一 `AlarmBatchChunker`，将单条 SQL、单次 JDBC batch 和分片内业务写批次硬限制为最多 `500` 条。异常逐条降级默认最多 `100` 条，超过上限整体重试，不在 MQ consumer 或 stop worker 线程中展开无界 N+1 SQL。

这项修改的目标是控制长事务、锁等待、redo/undo 压力和失败回滚成本，不直接承诺提升 stop 闭环吞吐。`countPending/selectPendingBatch` 热路径和 scheduled 串行 worker 仍是下一阶段重点，真实性能结论需要在 PROCESSING claim worker 完成后重新压测。

第一阶段真实回归结果：

| 场景 | RunId | Send elapsed | True closed-loop elapsed | 吞吐 | 结果 |
| --- | --- | ---: | ---: | ---: | --- |
| `GENERAL_ALTERNATE 10000` | `SQLGOV-GALT-10K-20260531000155` | `853ms` | `57859ms` | `172.83 rows/s` | PASS |
| `MIXED_ALTERNATE 10000` | `SQLGOV-MALT-10K-20260531000438` | `444ms` | `70783ms` | `141.28 rows/s` | PASS |

两轮使用 Spring AMQP consumer batch，Rabbit consumer 从 `2` 扩到 `4`。结果证明 500 条 SQL 硬边界没有破坏闭环一致性，但吞吐仍明显低于 75 万积压 30 分钟目标所需的 `417 rows/s`，第二阶段必须继续替换串行 stop worker。

## 2026-05-31 PROCESSING claim 待验证口径

第二阶段已经把串行 scheduled worker 替换为 `PROCESSING + lock_token` 短事务认领和 stop 专用线程池。初始参数为 `workerThreads=4`、`claimBatchSize=200`、`maxInFlightBatches=4`。单批 SQL 仍不超过 `500`，不通过放大事务换吞吐。

verifier 改为轻量热采样：每轮只读取 RabbitMQ 队列和当前 run 的 stop 状态；默认每 `5` 轮或 stop 接近终态时才完整统计物理分片、route 和 side effect。终态门禁新增 `alarm_stop_event.PROCESSING=0`。这样压测结果更接近业务闭环本身，不会被 verifier 高频 count 明显干扰。

待重新执行：`10000 -> 50000 -> 100000 -> 750000 PENDING`。75 万门禁仍为 30 分钟内完成、平均吞吐 `>= 417 rows/s`、最终 `PENDING=0`、`PROCESSING=0`、`FAILED=0`。

## 2026-06-01 2w MQ/min 最小优化待验证口径

本轮增加：

- stop 连续消息批量 upsert 的批次大小和耗时。
- stop event 首次可用延迟，灰度候选为 `100 / 200 / 300ms`。
- start 分阶段耗时：handle、electrolytic 历史、ectype 快照、alarm 主表、route、补偿处理。
- ectype 快照批次候选：`500 / 100 / 50`，默认候选为 `100`。
- ectype 快照死锁、有限整体重试、受限 fallback 次数。
- token/version 更新数量不一致的正确性门禁。

通过标准：

- mixed 持续吞吐 `>= 333.33 MQ/s`。
- 核心关闭吞吐 `>= 166.67 alarm/s`。
- `20000 MQ` 核心闭环 `<= 120s`。
- queue 和 pending 在持续输入期间不线性增长。
- 终态 `PENDING=0`、`PROCESSING=0`、`FAILED=0`。
- deadlock、逐条 fallback、持续 requeue 均为 `0`。
- `750000 PENDING` 在 `30min` 内完成核心关闭。

每档第一轮作为 warm-up，第二轮才进入正式结论。若任一核心指标未通过，输出 `报警2wMQ每分钟未达标拆分分析-YYYYMMDD.md`，并只选择一个最大瓶颈进入下一阶段。

## 2026-06-02 Phase A 真实 MQ + MySQL 结果

短时正式轮：

| 场景 | Run ID | MQ 数量 | 核心闭环 | 关闭吞吐 | 结果 |
| --- | --- | ---: | ---: | ---: | --- |
| `GENERAL_ALTERNATE` | `P2W-GALT-FORMAL-20260602001620` | `20000` | `14283ms` | `700.13 alarm/s` | PASS |
| `ELECTROLYTIC_ALTERNATE` | `P2W-EALT-FORMAL-20260602001709` | `20000` | `23030ms` | `434.22 alarm/s` | PASS |
| `MIXED_ALTERNATE` | `P2W-MALT-FORMAL-20260602001805` | `20000` | `19131ms` | `522.71 alarm/s` | PASS |

持续输入正式轮：

| 场景 | Run ID | MQ 数量 | 发送吞吐 | 核心关闭吞吐 | 终态 |
| --- | --- | ---: | ---: | ---: | --- |
| `MIXED_ALTERNATE` | `P2W-MALT-SUSTAINED-20260602003005` | `200000` | `333.22 MQ/s` | `165.17 alarm/s` | `APPLIED=100000`、`route CLOSED=100000`、`side effect DONE=55000` |

持续输入期间队列最大 `ready=5`，stop event 最大 `PENDING=281`、最大 `PROCESSING=170`，没有线性堆积。核心性能已经接近 `20000 MQ/min`，但没有达到 `>= 333.33 MQ/s` 和 `>= 166.67 alarm/s` 的门禁。

同步 `alarm_electrolytic_cell_ectype` 快照 upsert 仍出现 `8` 次 `PERSIST_LOCK_RETRY`，且 `initialAvailableDelayMs=200` 下记录 `1059` 批 `ROUTE_MISSING_RETRY`。因此本轮判定为生产门禁未通过，不继续放大 `50000 / 100000 MQ` 和 `750000 PENDING`。

下一阶段只选择电解槽快照异步投影拆分，详见：

- `doc/报警2wMQ每分钟未达标拆分分析-20260602.md`
- `doc/报警电解槽快照异步拆分实施计划-20260602.md`

## 2026-06-02 stop 可靠性审核修复灰度结果

本轮补齐三个可靠性缺口：

- `PROCESSING` 普通异常释放和超时回收使用有限重试，达到 `safeMaxRetry()` 后转 `FAILED`。
- 电解槽单条 fallback 在 `ASYNC` 下只写可靠快照命令，不再绕过命令表直接写投影。
- start 后到补偿不再吞掉 side effect outbox 创建异常；outbox 落库失败会回滚核心关闭。

focused 测试已通过：`Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`。从父工程执行 hpis-alarm
全量单测也已通过：`Tests run: 80, Failures: 0, Errors: 0, Skipped: 2`。

### Nacos 旧基线阻断

首次按本机 Nacos 原配置直接启动时，`hpis-alarm-dev.yml` 只有旧的 `alarm.sharding` 和
`alarm.stop-worker` 基础配置，没有 `alarm.batch.*`、`alarm.internal-test.*` 和异步快照 worker
候选参数。服务因此回落到旧单条 listener，Rabbit 从 `10` 个消费者扩展到 `36` 个；start 因测试
外部调用没有 stub 持续失败并 requeue，stop 先落成 `8999` 条 `FAILED/ROUTE_MISSING`，队列保留
`11001` 条消息。

该轮立即停止放大并保留现场。随后仅使用本地 JVM 临时覆盖候选参数重启服务，Rabbit 回到 consumer
batch 的 `2-4` 个消费者，积压清空，`10000` 条报警和 route 最终全部关闭，历史
`FAILED/ROUTE_MISSING` 全部恢复为 `APPLIED`。这个过程证明恢复路径有效，但也说明共享灰度前必须
先补齐 Nacos 候选参数。

### 候选参数短灰度

重新执行 `GENERAL_ALTERNATE / ELECTROLYTIC_ALTERNATE / MIXED_ALTERNATE` 的 `20000 MQ` warm-up 和
formal。正式轮结果：

| 场景 | Run ID | Send elapsed | True closed-loop elapsed | 关闭吞吐 | 终态 |
| --- | --- | ---: | ---: | ---: | --- |
| `GENERAL_ALTERNATE` | `P2W-GALT-FORMAL-20260602222624` | `2472ms` | `16866ms` | `592.91 alarm/s` | `alarmRows=closedRows=APPLIED=10000` |
| `ELECTROLYTIC_ALTERNATE` | `P2W-EALT-FORMAL-20260602222732` | `3927ms` | `29947ms` | `333.92 alarm/s` | `alarmRows=closedRows=APPLIED=10000`、`ecRows=10000`、`ecMissing=0` |
| `MIXED_ALTERNATE` | `P2W-MALT-FORMAL-20260602222841` | `1796ms` | `15516ms` | `644.50 alarm/s` | `alarmRows=closedRows=APPLIED=10000`、`ecRows=4500`、`ecMissing=0` |

`MIXED_ALTERNATE` formal 首次生成 manifest 时遇到 Windows 瞬时文件映射锁，消息尚未发送；复用同一
run 数据单独重跑后通过，不计为业务失败。

终态 SQL 和日志：

- Rabbit `ready=0`、`unacked=0`。
- stop event 全库 `FAILED=0`、`PROCESSING=0`、`PENDING=0`。
- 电解槽快照命令 `DONE=48`，没有持续 `PENDING/PROCESSING/FAILED`。
- 服务日志 `ERROR=0`、`FALLBACK_SINGLE=0`、`PROCESSING_TIMEOUT=0`、`PERSIST_LOCK_RETRY=0`、
  `deadlock=0`、`ROUTE_MISSING_PROFILE=0`。

结论：本轮三项可靠性修复通过 2 万 MQ 短灰度。生产化仍定位为候选，不直接合并上线；先把候选配置
同步到 Nacos，再继续执行 `20000 MQ/min x 10min`、`50000 / 100000 MQ` 和 `750000 PENDING`
积压门禁。
