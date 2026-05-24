# 本轮修改记录：ROUTE_MISSING 可重试与压测计时修正

## 修改背景

2026-05-24 真实 RabbitMQ + MySQL 压测中，`GENERAL_ALTERNATE + 50000` 出现 3 条 `alarm_stop_event FAILED/ROUTE_MISSING`。复核发现对应 `alarm_cid_index` route 后续已存在且仍为 `ACTIVE`，主表报警存在但未闭合，说明高并发 start/stop 交替下存在 route 暂不可见被误判为永久缺失的问题。同时旧压测报告把 verifier 启动空窗计入闭环耗时，导致 `2000` 档看起来比更大数据量更慢。

## 修改范围

- `AlarmStopEventService`：`ROUTE_MISSING` 默认改为 `PENDING + retry`，达到 `maxRetry` 后才转 `FAILED`；无 PENDING 时增加 `FAILED/ROUTE_MISSING` 恢复扫描。
- `AlarmStopEventMapper` 与 XML：新增 recoverable 查询、失败 route missing 扫描、批量 retry 方法。
- `AlarmStopWorkerProperties`：新增 `routeMissingRecoveryBatchSize`、`routeMissingRecoveryDelayMs`。
- `AlarmStopEventWorker`：新增 worker 轮次指标日志。
- `AlarmMqLoadVerifierMain`：拆分 `verifierStartupGap`、首次观测、队列清空、DB 闭合和可信闭环耗时。
- `AlarmMqLoadOrchestratorMain`：新增先启动 verifier、再发送 MQ 的压测入口。
- `AlarmMqLoadSummaryReportMain`：汇总报告改用可信闭环口径。
- `agent.md`：补充父子工程 Maven 测试规则。

## 外部入口是否变化

MQ 队列、消息体、Controller 和业务 Service 对外签名不变。新增的 `AlarmMqLoadOrchestratorMain` 仅作为 `src/test/java` 下压测工具入口。

## 数据库表结构是否变化

不新增表、不改列。复用 `alarm_stop_event.event_status/retry_count/last_error/updated_time`。

## MQ 消息体和 push payload 是否兼容

兼容。start/stop 消息体不变，push payload 不变。

## 幂等风险

`FAILED/ROUTE_MISSING` 恢复扫描只处理 `last_error=ROUTE_MISSING` 的失败事件；命中 ACTIVE route 后仍走原 `applyRouteGroup`，按 route 的 `alarm_id/table_suffix` 更新主表、关闭 route、标记 event `APPLIED`。重复 stop 命中 CLOSED route 时仍按 APPLIED 幂等成功处理。

## 数据一致性风险

新语义降低了 start/stop 乱序导致的半成品风险。风险是确实不存在 start 的坏 stop 会在 `maxRetry` 前多保留几轮 PENDING；达到 `maxRetry` 后仍会 FAILED，不会永久热重试。

## 可观测性变化

新增 `ROUTE_MISSING_RETRY`、`ROUTE_MISSING_FAILED`、`ROUTE_MISSING_RECOVER`、`BATCH_STOP_CORE` 日志，记录批次、样本 alarmCid、核心 SQL 阶段耗时。压测报告新增 verifier 启动空窗和可信闭环耗时字段。

## 配置开关和回滚方式

- `alarm.batch.stopEnabled=false` 可回滚到旧单条 stop worker 链路。
- `alarm.stop-worker.maxRetry` 控制 route missing 最多重试次数。
- `alarm.stop-worker.routeMissingRecoveryBatchSize` 控制失败恢复扫描批大小。
- `alarm.stop-worker.routeMissingRecoveryDelayMs` 控制 FAILED 后延迟多久进入恢复扫描。
- 压测工具可继续使用旧 sender/verifier，也可使用新的 orchestrator 获取干净计时。

## 已执行验证

- 父工程边界编译：`mvn -pl hpis-alarm -am -DskipTests compile`，通过。
- 目标单测：使用父工程编译产物和独立测试输出目录运行 JUnit，`AlarmStopEventServiceTest`、`AlarmMqLoadVerifierRunnerTest`、`AlarmMqDirectSenderRunnerTest` 共 9 个用例通过。

说明：常规 Maven testCompile 被 Windows 文件锁阻断，锁定文件为 `target/test-classes/...AlarmMqDirectSenderMain$SendOrderMode.class`；未停止 Nacos、RabbitMQ、MySQL 或正在运行的 hpis-alarm 服务。

## 未覆盖风险和下一步建议

- 需要重启 hpis-alarm 后从 `GENERAL_ALTERNATE + 50000` 重新压测，确认 `FAILED=0`、`PENDING=0`、`closedRows=alarmRows`。
- 性能结论只采纳 orchestrator 生成且 `trueClosedLoopElapsedMs != -1` 的 run。
- 正确性通过后再做 stop worker 参数实验，不建议先放大 MQ consumer 并发。
