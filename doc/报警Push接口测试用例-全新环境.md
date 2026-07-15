# Alarm → Push 全流程接口测试与实测结果

## 1. 结论与范围

本轮已完成一条可重复执行的基线链路：

```text
报警配置 API CRUD
  → Push 配置 API CRUD
  → HTTP /alarm/alarmAdd 或真实 RabbitMQ alarm_queue
  → Alarm 按 tenantId + sceneType + deviceSn + alarmType 匹配配置
  → Alarm 事务提交后发送 push.alarm
  → Push 按 tenantId + deviceSn#messageType 解析路由
  → HTTP Consumer
  → 最终接收器
```

2026-07-14 本机实测运行号：`20260714225038`。Alarm/Push 配置的新增、列表、详情、修改、启禁用和删除全部走接口；SQL 只用于问题定位时只读核对，没有用于配置写入或清理。

本轮明确不包含：

- `pushBindingId` 与推送绑定组；
- 企业微信；
- 工单创建、工单转派和 `messageType=25`；
- 微信公众号（需求已更正为企业微信，仍属于后续设计）。

这些后续能力的设计保存在 `docs/superpowers/specs/2026-07-14-alarm-push-binding-group-wecom-design.md`，不混入当前基线验收。

## 2. 测试原则和配套文件

1. `alarm_configure`、`alarm_device_configure`、`active_push_config`、`pushConfigId_deviceSn` 的测试配置不得使用 SQL 新增、修改或删除。
2. 新增接口通常不返回配置 ID，必须再调用列表接口取得 ID。
3. 每次修改后必须调用详情接口回读；每次删除后也必须调用详情接口确认不可见。
4. 租户只取当前登录用户租户，忽略请求体中的 `tenantId`。
5. 真实链路使用临时用户、租户和设备 Redis 缓存；脚本结束时删除临时缓存并停止进程。

配套文件：

| 文件 | 用途 | 当前状态 |
|---|---|---|
| `src/test/resources/scripts/run-alarm-push-e2e.ps1` | 自动启动 Alarm/Push/HTTP 接收器，执行配置 CRUD、HTTP/MQ 链路、租户隔离和清理 | 本轮实际执行入口 |
| `src/test/resources/http/alarm-push-api.http` | 手工 HTTP Client 请求实例 | 与本文当前范围一致 |
| `src/test/resources/scripts/start-alarm-push-http-receiver.ps1` | HTTP 回调接收器和 `/_events` 查询 | 已实测 |
| `src/test/resources/postman/*` | 旧工单/25 场景集合 | 历史资料，本轮不要执行 |

全自动执行命令：

```powershell
cd D:\studyProject\hpis2.0\hpis
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File hpis-alarm\src\test\resources\scripts\run-alarm-push-e2e.ps1
```

## 3. 环境与公共变量

| 项目 | 本轮值 |
|---|---|
| Alarm | `http://127.0.0.1:18806` |
| Push | `http://127.0.0.1:8812` |
| HTTP 接收器 | `http://127.0.0.1:19010` |
| RabbitMQ | `127.0.0.1:5672`，管理端 `15672` |
| Redis | `127.0.0.1:6379` |
| Nacos | `127.0.0.1:8848` |
| 测试租户/用户 | `799001` |
| 隔离租户/用户 | `799002` |
| 测试设备 ID | `79900101` |
| 测试设备 SN | `CODEX-E2E-DEVICE-<runId>` |
| 报警配置名 | `e2e-alarm-<runId>` |
| Push 配置名 | `e2e-push-<runId>` |

直连微服务时，除 `Authorization` 外还要带网关平时会补充的用户头：

```http
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
Content-Type: application/json
```

通过正式网关执行时，使用正常登录 Token，由网关生成内部用户头，不应手工伪造。

## 4. Alarm 配置 CRUD 实例与结果

### AC-01 新增

```http
POST http://127.0.0.1:18806/configure/add
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
Content-Type: application/json

{
  "alarmConfigureName": "e2e-alarm-20260714225038",
  "alarmType": "10",
  "deviceAlarmControl": "1",
  "alarmConfigurePeriod": "0",
  "sceneType": "1",
  "deviceIds": [79900101],
  "pushEnabled": "1",
  "pushMessageType": "10",
  "workorderConfigId": 0
}
```

预期：`code=200`；服务端把租户强制设为 `799001`；类型 10 也根据 `deviceIds` 建立设备关系；`workorderConfigId=0` 表示未关联。

实测：PASS。新增接口成功，列表能回查，详情返回 `deviceIds=[79900101]` 和对应 `deviceSet`。

### AC-02 列表

```http
GET http://127.0.0.1:18806/configure/list?pageNum=1&pageSize=100&alarmConfigureName=e2e-alarm-20260714225038
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
```

预期：`code=200`，`rows` 中只有当前租户命中的配置，并取得 `alarmConfigureId`。

实测：PASS。当前租户可见；隔离租户 `799002` 使用相同条件查询时 `rows=[]`。

### AC-03 详情

```http
GET http://127.0.0.1:18806/configure/{alarmConfigureId}
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
```

关键断言：

```json
{
  "code": 200,
  "data": {
    "alarmType": "10",
    "tenantId": 799001,
    "deviceIds": [79900101],
    "pushEnabled": "1",
    "pushMessageType": "10",
    "workorderConfigId": 0
  }
}
```

实测：PASS。隔离租户查询同一 ID 时 `data=null`。

### AC-04 修改

```http
PUT http://127.0.0.1:18806/configure/update
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
Content-Type: application/json

{
  "alarmConfigureId": {alarmConfigureId},
  "alarmConfigureName": "e2e-alarm-20260714225038-u",
  "alarmType": "10",
  "deviceAlarmControl": "1",
  "alarmConfigurePeriod": "0",
  "sceneType": "1",
  "deviceIds": [79900101],
  "pushEnabled": "1",
  "pushMessageType": "10",
  "workorderConfigId": 0
}
```

预期：`code=200`；详情回读新名称；设备关系不丢失。请求不传 `deviceIds` 时保留原关系，传空数组时清空关系。

实测：PASS。修改后详情名称为 `...-u`，绑定仍为测试设备。

### AC-05 删除

```http
DELETE http://127.0.0.1:18806/configure/delete/{alarmConfigureId}
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
```

预期：`code=200`；详情再次查询 `data=null`；设备关系、时间段关系同步清理。

实测：PASS。整个清理过程走删除接口，未使用配置 SQL。

## 5. Push 配置 CRUD 实例与结果

Push HTTP 通道为 `pushChannelType=10`。当前 `PushHttpConsumer` 会自行补 `http://`，因此 `pushAddress` 必须写成 `host:port/path`，不能包含协议头。

### PC-01 新增（先禁用）

```http
POST http://127.0.0.1:8812/pushConfig/add
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
Content-Type: application/json

{
  "messageType": "10",
  "pushChannelType": "10",
  "enabled": false,
  "pushAddress": "127.0.0.1:19010/codex/alarm-10",
  "isPassive": "0",
  "configName": "e2e-push-20260714225038",
  "deviceSns": ["CODEX-E2E-DEVICE-20260714225038"]
}
```

预期：HTTP/业务成功；数据库强制写当前租户和 `del_flag=0`；禁用状态不创建有效投递 Consumer。

实测：PASS。本轮修复前曾出现“新增成功但 `del_flag=NULL` 导致列表查不到”，修复后新增、回查均成功。

### PC-02 列表

```http
GET http://127.0.0.1:8812/pushConfig/list?pageNum=1&pageSize=100&configName=e2e-push-20260714225038
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
```

预期：`rows` 返回当前租户配置并取得 `activePushConfigId`。

实测：PASS。实际配置 ID：`2077043211721396224`；隔离租户查询结果为空。

### PC-03 详情

```http
GET http://127.0.0.1:8812/pushConfig/{activePushConfigId}
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
```

关键断言：`messageType=10`、`pushChannelType=10`、`enabled=false`、`tenantId=799001`，并返回测试 `deviceSns`。

实测：PASS。隔离租户查询同一 ID 时 `data=null`。

### PC-04 修改并启用

```http
POST http://127.0.0.1:8812/pushConfig/update
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
Content-Type: application/json

{
  "activePushConfigId": 2077043211721396224,
  "messageType": "10",
  "pushChannelType": "10",
  "enabled": true,
  "pushAddress": "127.0.0.1:19010/codex/alarm-10",
  "isPassive": "0",
  "configName": "e2e-push-20260714225038-u",
  "deviceSns": ["CODEX-E2E-DEVICE-20260714225038"]
}
```

预期：提交后重建路由、配置队列和唯一 HTTP Consumer；详情返回 `enabled=true` 和新名称。

实测：PASS。日志显示 Consumer 监听 `config.queue.ws.direct.exchange.2077043211721396224`。

### PC-05 禁用

使用 PC-04 完整请求体，只把 `enabled` 改为 `false`。

预期：事务提交后移除运行路由、停止 Consumer、删除配置队列；再产生报警时接收器计数不增加。

实测：PASS。禁用后的新报警仍正常落入 Alarm，但 HTTP 接收计数保持不变；日志显示 Consumer 主动停止。

### PC-06 删除

```http
DELETE http://127.0.0.1:8812/pushConfig/{activePushConfigId}
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
```

预期：删除配置及设备关系，并清理 Consumer、队列、Redis 路由和 pushKey 运行关系；详情回查 `data=null`。

实测：PASS。配置清理全部走接口。

## 6. 报警入口与最终接收

### AP-01 HTTP 模拟入口

```http
POST http://127.0.0.1:18806/alarm/alarmAdd
Authorization: Bearer <token>
user_id: 799001
username: codex-e2e
Content-Type: application/json

{
  "alarmId": "CODEX-E2E-HTTP-20260714225038",
  "deviceSn": "CODEX-E2E-DEVICE-20260714225038",
  "gatewaySn": "CODEX-E2E-GATEWAY-20260714225038",
  "alarmType": "10",
  "alarmDegree": "1",
  "sceneType": "1",
  "cameraType": "1",
  "tenantId": 799001,
  "time": "2026-07-14 22:51:08"
}
```

注意：当前 Controller 返回空的 HTTP 2xx，不返回业务 JSON，最终必须以 Alarm 记录、MQ 日志和接收结果判定。

实测：PASS。Alarm 生成内部 ID `671599294431815216`；Push HTTP 回调成功并写入成功日志。

### AP-02 真实 `alarm_queue`

投递到默认交换机，routing key 为 `alarm_queue`。消息体必须符合现场 `dataSync → cmdData → rawData`，其中 `cmdSeq` 是 int，不能使用 14 位时间戳。

```json
{
  "cmd": "dataSync",
  "cmdData": {
    "confItems": 1000,
    "deviceSn": "CODEX-E2E-GATEWAY-20260714225038",
    "operCode": 259,
    "rawData": {
      "alarmDegree": "1",
      "alarmId": "CODEX-E2E-MQ-20260714225038",
      "alarmType": "10",
      "cameraType": "1",
      "deviceSn": "CODEX-E2E-DEVICE-20260714225038",
      "gatewaySn": "CODEX-E2E-GATEWAY-20260714225038",
      "sceneType": "1",
      "time": "2026-07-14 22:51:08"
    },
    "version": 1
  },
  "cmdSeq": 1,
  "servId": "codex-e2e",
  "times": 1
}
```

自动脚本通过 RabbitMQ Management API `POST /api/exchanges/%2F/amq.default/publish` 发布，上述 JSON 作为 `payload`，`payload_encoding=string`。

实测：PASS。管理 API 返回 `routed=true`；Alarm 生成内部 ID `671599294431815217`；同一 HTTP 接收器得到第二条回调。

### AP-03 最终回调检查

```http
GET http://127.0.0.1:19010/_events
```

关键断言：至少两条事件，分别包含 HTTP/MQ 的外部 `alarmId`，且顶层 `messageType=10`、`tenantId=799001`、`deviceSn` 为测试设备。

实际 Push 证据：

| 入口 | Push messageId | 目标 | 状态 |
|---|---:|---|---|
| HTTP | `2077043222227623936` | `127.0.0.1:19010/codex/alarm-10` | 成功 |
| `alarm_queue` | `2077043224744206336` | `127.0.0.1:19010/codex/alarm-10` | 成功 |

## 7. 通道接收矩阵

| 通道 | channelType | 本轮状态 | 结论/后续执行条件 |
|---|---:|---|---|
| HTTP 主动推送 | `10` | PASS | 已完成配置 API、HTTP/MQ 两入口和最终接收实测 |
| MQTT 主动推送 | `11` | 未执行 | 代码已支持；本机没有在范围内准备独立 MQTT Broker/订阅器，不伪造 PASS |
| 普通 WebSocket session | 会话队列 | 未执行 | 需要真实 WS Client：先 `POST /session/getSessionId`，连接 `/pushWebsocket` 后首帧发送 `{"sessionId":"..."}` |
| pushKey WebSocket | `1` 被动 | 未执行 | 创建被动配置后调用 `/pushConfig/bindPushConfigIds`，首帧发送 `{"pushKey":"..."}`；收到带 `deliveryId` 的消息后回执 |
| 短信 | `30` | 不纳入 | 枚举存在，但当前配置服务没有创建对应 Consumer，不能作为已支持通道 |
| 邮件 | `31` | 不纳入 | 同上 |
| 企业微信 | 后续新增 | 不纳入 | 等 pushBindingId/租户内用户绑定设计实施后再测 |

pushKey WebSocket 回执格式：

```json
{
  "ReceiptMessage": "<deliveryId>",
  "messageStatus": true
}
```

## 8. 租户与生命周期结果

| 用例 | 预期 | 本轮结果 |
|---|---|---|
| 请求体伪造 `tenantId` | 新增仍写当前租户 | PASS（单元测试） |
| 租户 799002 查 Alarm 列表/详情 | 列表空、详情空 | PASS（E2E） |
| 租户 799002 查 Push 列表/详情 | 列表空、详情空 | PASS（E2E） |
| 跨租户更新/批量删除 | 拒绝整个请求 | PASS（单元测试） |
| Push 禁用 | 停止 Consumer，后续不投递 | PASS（E2E） |
| Push 删除 | 路由/队列/Consumer/关系清理 | PASS（E2E） |
| Push 冷启动恢复 | DB 启用配置重建路由/Consumer | PASS（服务启动日志） |

## 9. 本轮自动结果

脚本最终输出：

```json
{
  "runId": "20260714225038",
  "alarmStartup": true,
  "pushStartup": true,
  "orphanCleanup": true,
  "alarmCreate": true,
  "alarmRead": true,
  "alarmUpdate": true,
  "pushCreate": true,
  "pushRead": true,
  "pushEnable": true,
  "httpIngressReceived": true,
  "mqIngressReceived": true,
  "pushDisableStopsDelivery": true,
  "tenantIsolation": true,
  "pushDelete": true,
  "alarmDelete": true,
  "errors": []
}
```

自动化/启动验证汇总：

| 验证 | 结果 |
|---|---|
| Alarm 定向单测 | 17 个，0 失败 |
| Push 定向单测 | 9 个，0 失败 |
| Alarm 父工程打包 | PASS |
| Push 父工程打包 | PASS |
| Alarm 启动、分片刷新、`/alarm/list` smoke | PASS |
| Push 启动和 DB 配置恢复 | PASS |
| Alarm → Push 真实 E2E | PASS |

环境噪声：本机 Nacos 的 HTTP 登录接口会周期性超时，且未启动 `hpis-system` 日志服务，因此有 Nacos 登录和 RemoteLog fallback 日志；两个业务服务仍能从配置快照启动、完成 gRPC 注册和本轮业务验收。

## 10. 已发现并修复的问题

1. Alarm 配置列表把 `deviceIds` 等虚拟字段当数据库列，运行时报 `Unknown column`；已标记为非表字段。
2. Alarm 类型 10/6 原先不根据 `deviceIds` 建立关系；已统一为所有类型按当前租户设备缓存解析。
3. Alarm 配置 CRUD 原先租户约束不完整；已强制当前租户并限制详情、更新、删除。
4. 类型 10 名称解析可能空指针；已补名称和未知类型兜底。
5. 无匹配报警配置时仍可能兼容推送；新增 `alarm.push.require-matched-config`，默认 `true`。
6. Push 配置新增时 `del_flag=NULL`，导致“新增成功但列表/详情不可见”；已显式写 `0`，并兼容读取历史空值。
7. Push 使用 Lambda Wrapper 的启动查询在当前 MyBatis 组合下参数绑定失败；改为明确 Mapper SQL。
8. Push CRUD、pushKey 绑定/解绑缺少完整租户限制；已按当前租户收口。
9. Push HTTP/MQTT 更新、禁用、删除缺少显式 Consumer 停止；已加入按配置 ID 的停止句柄并在提交后处理运行态。
10. E2E 初版 MQ 报文把 14 位 `runId` 作为 int `cmdSeq`，被协议解析拒绝；测试实例已改为合法整数 `1`。

## 11. 回滚与未解决边界

- 若上线后必须临时恢复“没有匹配 Alarm 配置也继续按旧逻辑推送”，设置：

  ```yaml
  alarm:
    push:
      require-matched-config: false
  ```

- 该开关只回滚 Alarm 配置匹配门禁，不回滚租户隔离、设备绑定或 Push 生命周期修复。
- `/alarm/alarmAdd` 当前吞掉业务异常并返回空 2xx，接口调用方不能只看响应判断成功；本轮以最终接收闭环规避，建议在单独兼容性评审后再调整响应协议。
- MQTT、普通 WebSocket、pushKey WebSocket 本轮只完成流程调查和测试实例设计，没有在当前环境执行，不应写为 PASS。
