# 本轮修改记录 - 20260523 - 清理自研聚合保留 consumer batch

## 修改背景

前一轮同时保留了旧单条 `RabbitMQAlarmListener`、自研 `AlarmInsertAggregateService.submitAndWait` 聚合、Spring AMQP `RabbitMQAlarmBatchListener` 三条 MQ start 路径。压测已经证明 submitAndWait 聚合吞吐低于旧单条，继续保留会让配置、测试、排障和后续 backlog 自适应方案分叉。

本轮按低风险收敛：只保留旧单条 listener 和 Spring AMQP consumer batch；consumer batch 默认仍关闭，生产灰度只需要开启 `alarm.batch.insertConsumerBatchEnabled=true`。

## 修改范围

- 删除 `AlarmInsertAggregateService` 和 `AlarmInsertAggregateServiceTest`，旧 `alarm.batch.insertAggregate*` 配置不再有代码读取入口。
- `RabbitMQAlarmListener` 删除聚合服务注入和 `submitAndWait` 分支，start 消息固定回到 `alarmService.insertAlarm(rawData)`。
- `AlarmInsertAggregateResult` 重命名为 `AlarmInsertConsumeResult`，只表达 consumer batch 每条 start 消息是否应该 ack。
- `RabbitMQAlarmBatchListener` 调整同批处理顺序：先解析分类，坏消息 reject；先批量处理 start 并逐条 ack/nack；再按原相对顺序处理 stop/status。
- `AlarmInsertBatchConsumeService` 保持 prepare 一次、批量事务、失败拆单复用 context 的边界，仅替换结果枚举命名。
- 文档同步更新运行配置、分片链路、第一阶段总览和压测报告。

## 外部入口和兼容性

- Controller、MQ queue、MQ 消息体、push payload 均不变化。
- `insertAlarm(JSONObject)` 外部单条入口不变化。
- `insertAlarms(List<JSONObject>)` 内部批量 API 保留。
- `alarm.batch.insertConsumerBatchEnabled=false` 时仍走旧单条 listener；设置为 true 时旧 listener 条件关闭，batch listener 接管同一队列。

## 数据库和消息语义

- 不修改数据库表结构和 mapper SQL 结构。
- MQ ack/nack 仍由 listener 当前线程逐条执行，不使用 `multiple=true`。
- `SUCCESS/SKIP/DROP` ack，`FAIL` nack/requeue，协议坏消息 reject 且不 requeue。
- 同批 start 先于同批 stop/status 处理，用于降低 route 尚未写入时 stop 被误判 `ROUTE_MISSING` 的窗口。

## 风险评估

1. 当前代码核心问题：三条 MQ start 路径并存，配置和文档容易误导生产灰度。
2. 最大业务风险：同批 start/stop 如果 stop 先执行，可能扩大 `ROUTE_MISSING` 误 FAILED 窗口；本轮已调整为先 start 后 direct。
3. 幂等性风险：MQ 重投仍依赖 `alarmCid`、Redis 断线去重、route/stop 状态机；本轮不改变这些兜底。
4. 数据一致性风险：批量失败拆单必须复用 `AlarmInsertContext`，本轮未改变该边界，并新增/调整测试覆盖。
5. 可观测性问题：删除 aggregate 后，日志和文档只剩 consumer batch 口径；灰度时建议临时开启 `insertConsumerBatchLogEnabled=true`。
6. 扩展性问题：收敛到 consumer batch 后，后续 backlog 自适应和动态 batch size 只需要维护一套模型。
7. 推荐低风险第一步：本轮即删除自研聚合入口，旧单条 listener 作为默认回滚路径。
8. 后续第二步、第三步：第二步补混合 start/stop 压测；第三步再接 MQ backlog 或消费 lag 指标自动调整 batch 参数。
9. 验收标准：只有旧单条 listener 和 batch listener 两个消费入口；开启 batch 时旧 listener 条件关闭；start 成功 ack、FAIL nack/requeue、坏消息 reject。
10. Grill me 问题：生产是否确认配置中心清理 `insertAggregate*` 残留；consumer batch 灰度时是否接受同一 `alarmCid` 不严格串行。

## 回滚方式

- 关闭 `alarm.batch.insertConsumerBatchEnabled`，应用回到旧 `RabbitMQAlarmListener` 单条 start/stop 入口。
- 如果需要重新实验异步聚合，应新建独立方案评审，不建议恢复本轮删除的 submitAndWait 内存队列模型。

## 已执行验证

- 本轮提交前执行 `git diff --check`。
- 执行 `mvn -pl hpis-alarm -am -DfailIfNoTests=false "-Dtest=RabbitMQAlarmListenerTest,RabbitMQAlarmBatchListenerTest,AlarmInsertBatchConsumeServiceTest" test`。
- 执行 `mvn -pl hpis-alarm -am -DskipTests compile`。

## 未覆盖风险

- 尚未在真实 RabbitMQ 环境压测混合 start/stop/status 的批量顺序和吞吐。
- 尚未验证多实例部署下不同实例同时开启 consumer batch 的 DB/Redis 压力上限。
