# Alarm-Push API 接口文档

## 1. 文档用途与阅读方式

本文面向接口测试人员，描述当前 `hpis-alarm` 和 `hpis-push` 的真实外部接口、字段来源、输入输出以及 Alarm → Push 路由规则。

推荐阅读顺序：

1. 第 2～3 章了解整体链路、认证、租户和响应结构。
2. 第 4～10 章按“报警配置 → 上报 → 记录 → 处理/工单 → Push”的顺序查接口。
3. 第 13～15 章查字段、枚举以及“为什么推送、推到哪里、推给谁”。
4. 需要直接执行测试时配合 [Alarm-Push-全流程测试使用手册](./Alarm-Push-全流程测试使用手册.md)。

本文以当前源码为准。旧测试报告与源码冲突时，以 Controller、领域对象、Service 校验和 MQ/Push 实现为准。

## 2. 整体链路

```text
报警配置 /configure
  ↓ tenantId + sceneType + deviceSn + alarmType
HTTP /alarm/alarmAdd 或 RabbitMQ alarm_queue(operCode=259)
  ↓
Alarm 生成内部 Long alarmId，外部 alarmId 保存为 alarmCid
  ↓ 事务提交
push.open=true 且命中启用的 Alarm 配置、pushEnabled=1
  ↓ pushMessageType
RabbitMQ push.alarm
  ↓ tenantId + messageType + DEVICE/TENANT 路由
ActivePushConfig
  ↓ pushChannelType
HTTP / MQTT / WebSocket / 企业微信
  ↓
目标地址、Topic、WS 会话，或企业微信接收组/工单负责人
```

关键边界：

- 报警记录先写入数据库；没有 Push 不等于没有报警记录。
- `/alarm/alarmAdd` 当前捕获业务异常并可能返回空 HTTP 2xx，必须通过 `alarmCid` 回查记录。
- 默认 `alarm.push.require-matched-config=true`：未匹配 Alarm 配置时跳过配置推送；设为 `false` 时才按旧兼容逻辑使用原始 `alarmType`。
- 普通报警没有 `assigneeId`，企业微信使用 Push 配置的接收组。
- 工单创建/转派消息带数值型 `assigneeId`，企业微信只发当前负责人，接收组不再参与。

## 3. 公共访问约定

### 3.1 服务地址与网关

| 服务 | 默认直连地址 | 说明 |
|---|---|---|
| hpis-alarm | `http://127.0.0.1:8806` | 报警配置、记录、处理和工单 |
| hpis-push | `http://127.0.0.1:8812` | Push 配置、企业微信和 Session |
| HTTP 测试接收器 | `http://127.0.0.1:19010` | 仓库测试脚本默认端口 |

正式环境应通过网关和正常登录 Token 调用。直连微服务仅用于受控测试环境。

### 3.2 鉴权与租户 Header

直连微服务时常用 Header：

```http
Authorization: Bearer ${TOKEN}
user_id: ${CURRENT_USER_ID}
username: ${CURRENT_USERNAME}
Content-Type: application/json
```

通过正式网关时只传正式登录 Token，由网关生成内部用户头，不应手工伪造。

租户规则：

- `tenantId` 取当前登录上下文 `SecurityUtils.getCurrentTenantId()`。
- 新增请求中的 `tenantId` 不用于切换租户。
- 列表、详情、修改和删除均应限制在当前租户。
- 跨租户测试必须使用另一个合法账号/Token，不能修改 Header 冒充。

### 3.3 分页、时间和 Long ID

| 项目 | 规则 |
|---|---|
| 分页 | `pageNum` 从 1 开始，`pageSize` 为每页条数 |
| 时间 | 报警入口使用 `yyyy-MM-dd HH:mm:ss` |
| 查询时间 | BaseEntity 常用 `startTime`、`endTime`；统计接口也可能使用 `AlarmQueryParameter` |
| Long ID | 前端/脚本建议按字符串保存，避免 JavaScript 丢失精度；请求 JSON 可传数值 |
| 唯一测试数据 | 配置名和 `alarmCid` 应包含本轮 `runId` |

### 3.4 公共响应结构

`AjaxResult`：

```json
{"code":200,"msg":"操作成功","data":{"id":900001}}
```

分页 `TableDataInfo`：

```json
{"code":200,"msg":"查询成功","total":1,"rows":[{"alarmId":671599294431815216}]}
```

通用 `R`：

```json
{"code":200,"msg":"操作成功","data":1}
```

空 2xx：`POST /alarm/alarmAdd` 当前返回体可能为空。空响应只表示 Controller 调用结束，不能证明报警已入库或已推送。

## 4. 报警配置接口 `/configure`

报警配置决定某租户、场景、设备、报警类型是否匹配，以及匹配后使用什么 Push messageType 和工单模板。

### 4.1 接口清单

| 方法 | 路径 | 用途 | 代码权限 |
|---|---|---|---|
| GET | `/configure/list` | 分页查询 | `alarm_configure:configure:list` |
| GET | `/configure/repeatConfig` | 查询一般行业重复报警配置 | 无显式权限注解 |
| POST | `/configure/export` | 导出 | `alarm_configure:configure:export` |
| GET | `/configure/{alarmConfigureId}` | 详情 | `alarm_configure:configure:query` |
| POST | `/configure/add` | 新增 | 权限注解当前已注释 |
| PUT | `/configure/update` | 修改 | 权限注解当前已注释 |
| DELETE | `/configure/delete/{alarmConfigureIds}` | 批量逻辑删除 | `alarm_configure:configure:remove` |

### 4.2 新增

```http
POST /configure/add
Content-Type: application/json
```

```json
{
  "alarmConfigureName": "TEST-ALARM-CONFIG-20260718",
  "alarmType": "10",
  "deviceAlarmControl": "1",
  "alarmConfigurePeriod": "0",
  "sceneType": "1",
  "deviceIds": [79900101],
  "pushEnabled": "1",
  "pushMessageType": "10",
  "workorderPushMessageType": "25",
  "workorderConfigId": 700001
}
```

字段设置依据：

- `alarmType`、`sceneType` 必须与准备上报的报警一致。
- `deviceIds` 使用当前租户真实设备 ID；服务会解析成设备 SN 关系。
- `deviceAlarmControl="1"` 表示启用设备报警。
- `alarmConfigurePeriod="0"` 表示全天；自定义时段使用 `"1"` 并传 `alarmConfigureTimeList`。
- `pushEnabled="1"` 才进入配置推送。
- `pushMessageType` 必须与普通报警的 Push 配置 `messageType` 一致。
- `workorderPushMessageType` 必须与工单 Push 配置 `messageType` 一致。
- `workorderConfigId=0` 表示不启用工单；创建工单要求正数模板 ID。

成功后用唯一名称回查 ID：

```http
GET /configure/list?pageNum=1&pageSize=20&alarmConfigureName=TEST-ALARM-CONFIG-20260718
```

### 4.3 详情与修改

```http
GET /configure/900001
```

修改必须传 `alarmConfigureId`。推荐先 GET 详情，再基于完整对象修改：

```json
{
  "alarmConfigureId": 900001,
  "alarmConfigureName": "TEST-ALARM-CONFIG-20260718-U",
  "alarmType": "10",
  "deviceAlarmControl": "1",
  "alarmConfigurePeriod": "0",
  "sceneType": "1",
  "deviceIds": [79900101],
  "pushEnabled": "1",
  "pushMessageType": "10",
  "workorderPushMessageType": "25",
  "workorderConfigId": 700001
}
```

`deviceIds` 修改语义：

| 请求值 | 结果 |
|---|---|
| 字段不传或 `null` | 保留原设备关系 |
| `[]` | 明确清空设备关系 |
| 非空数组 | 完整替换设备关系 |

设备 ID 必须为当前租户正整数；`workorderConfigId` 不能为负数。修改后必须再次 GET 详情检查 `deviceIds`、`deviceSet` 和推送字段。

### 4.4 删除

```http
DELETE /configure/delete/900001,900002
```

删除后调用详情应返回 `data=null`，设备关系和时间段关系也应清理。只能删除本轮创建的配置。

## 5. 报警上报入口

### 5.1 HTTP `/alarm/alarmAdd`

```http
POST /alarm/alarmAdd
Content-Type: application/json
```

```json
{
  "alarmId": "TEST-ALARM-CID-20260718-HTTP",
  "deviceSn": "TEST-DEVICE-SN-001",
  "gatewaySn": "TEST-GATEWAY-SN-001",
  "alarmType": "10",
  "alarmDegree": "1",
  "sceneType": "1",
  "cameraType": "1",
  "tenantId": 799001,
  "time": "2026-07-18 10:00:00"
}
```

| 字段 | 类型 | 必填 | 设置依据 |
|---|---|---:|---|
| alarmId | String | 是 | 外部 CID；同一次开始/停止必须一致 |
| deviceSn | String | 是 | 当前租户真实设备 SN，需命中 Alarm/Push DEVICE 配置 |
| gatewaySn | String | 场景相关 | 网关/IRMS SN |
| alarmType | String | 是 | 与 Alarm 配置 `alarmType` 一致 |
| alarmDegree | String | 是 | 报警级别原始值，常用 `1` |
| sceneType | String | 是 | 与 Alarm 配置、设备行业一致 |
| cameraType | String | 场景相关 | 设备/摄像机类型；断线等逻辑会使用 |
| tenantId | Long | 建议传 | 最终租户以设备和登录上下文解析结果为准 |
| time | String | 是 | `yyyy-MM-dd HH:mm:ss` |

输入字段 `alarmId` 最终保存为 `Alarm.alarmCid`；服务生成内部 Long `Alarm.alarmId`。回查：

```http
GET /alarm/list?alarmCid=TEST-ALARM-CID-20260718-HTTP&pageNum=1&pageSize=10
```

### 5.2 RabbitMQ `alarm_queue`

使用默认交换机、routing key `alarm_queue`。开始报警 `operCode=259`（十六进制 `0x0103`）：

```json
{
  "cmd": "dataSync",
  "cmdData": {
    "confItems": 1000,
    "deviceSn": "TEST-GATEWAY-SN-001",
    "operCode": 259,
    "rawData": {
      "alarmDegree": "1",
      "alarmId": "TEST-ALARM-CID-20260718-MQ",
      "alarmType": "10",
      "cameraType": "1",
      "deviceSn": "TEST-DEVICE-SN-001",
      "gatewaySn": "TEST-GATEWAY-SN-001",
      "sceneType": "1",
      "time": "2026-07-18 10:05:00"
    },
    "version": 1
  },
  "cmdSeq": 1,
  "servId": "alarm-api-test",
  "times": 1
}
```

停止报警 `operCode=260`（`0x0104`），复用同一 CID：

```json
{
  "cmd": "dataSync",
  "cmdData": {
    "confItems": 1000,
    "deviceSn": "TEST-GATEWAY-SN-001",
    "operCode": 260,
    "rawData": {
      "alarmId": "TEST-ALARM-CID-20260718-MQ",
      "deviceSn": "TEST-DEVICE-SN-001",
      "gatewaySn": "TEST-GATEWAY-SN-001",
      "time": "2026-07-18 10:10:00"
    },
    "version": 1
  },
  "cmdSeq": 2,
  "servId": "alarm-api-test",
  "times": 1
}
```

`cmdSeq` 必须是合法 int，不能直接使用 14 位时间戳。RabbitMQ `routed=true` 只说明进入队列，仍需回查 Alarm。

## 6. 报警记录接口 `/alarm`

### 6.1 CRUD 与主要查询

| 方法 | 路径 | 输入 | 输出/判定 |
|---|---|---|---|
| GET | `/alarm/list` | Alarm 字段 + `pageNum/pageSize` | `rows/total`；推荐按 `alarmCid` 精确查 |
| GET | `/alarm/query/{alarmId}` | 内部 Long ID | `AjaxResult.data` 详情 |
| POST | `/alarm/alarmAdd` | 第 5.1 节 JSON | 空 2xx；必须回查 |
| PUT | `/alarm` | Alarm JSON，`alarmId` 必填 | 影响行数包装为 AjaxResult |
| DELETE | `/alarm/{alarmIds}` | 逗号分隔 Long ID | 逻辑删除并同步子数据 |
| GET | `/alarm/getAlarmPicture/{alarmId}` | 内部 ID | 图片相关 Alarm 数据 |
| GET | `/alarm/getPictureByPath` | Alarm 查询参数 | 路径对应图片数据 |

修改示例：

```json
{
  "alarmId": 671599294431815216,
  "alarmStatus": "2",
  "identify": "0",
  "opinion": "接口测试确认"
}
```

只修改本轮记录；修改后用 `/alarm/query/{alarmId}` 回读。删除示例：

```http
DELETE /alarm/671599294431815216,671599294431815217
```

### 6.2 停止接口

按设备 SN：

```http
POST /alarm/alarmStopByDeviceSn
```

```json
{"deviceSn":"TEST-DEVICE-SN-001","time":"2026-07-18 10:10:00"}
```

按 IRMS SN：

```http
POST /alarm/alarmStopByIrmsSn
```

```json
{"irmsSn":"TEST-GATEWAY-SN-001","time":"2026-07-18 10:10:00"}
```

两者会批量关闭符合条件的活跃报警。单 CID 的 `alarmStop(JSONObject)` 当前没有独立 HTTP Controller；外部单条停止使用第 5.2 节 MQ `operCode=260`。停止成功应回查 `alarmStatus="1"`、`alarmEndtime` 非空；重复停止按幂等“无活跃报警”处理。

### 6.3 统计接口

| 方法 | 路径 | 主要参数 |
|---|---|---|
| GET | `/alarm/countAlarm` | Alarm 查询字段 |
| GET | `/alarm/count/list` | `deviceId`、`dateRange`、`customerId`，代码权限 `alarm:alarm:list` |
| GET | `/alarm/alarmTimeCountByMonth` | AlarmQueryParameter |
| GET | `/alarm/alarmModeCount` | AlarmQueryParameter |
| GET | `/alarm/alarmCountByTime` | AlarmQueryParameter |
| GET | `/alarm/AlarmOfDay` | AlarmQueryParameter；路径大小写按源码 |

AlarmQueryParameter 字段：`alarmType,startTime,endTime,deviceSn,sceneType,tenantId,alarmRank,alarmStatus,handleStatus,deviceIds`。`tenantId` 仍应由服务端当前租户控制。

`POST /alarm/export` Mapping 存在，但当前 Controller 方法体没有实际导出逻辑，不作为通过用例。

## 7. 报警处理接口 `/handle`

| 方法 | 路径 | 用途 | 权限 |
|---|---|---|---|
| GET | `/handle/list` | 分页查询处理记录 | `alarm:handle:list` |
| GET | `/handle/{alarmHandleId}` | 详情 | `alarm:handle:query` |
| POST | `/handle` | 新增处理记录 | `alarm:handle:add` |
| POST | `/handle/save` | 通用保存处理 | 无显式权限注解 |
| GET | `/handle/saveAll` | 电解槽批量保存；参数走 Query | 无显式权限注解 |
| POST | `/handle/update` | 确认处理 | 无显式权限注解 |
| DELETE | `/handle/delete/{alarmHandleIds}` | 删除 | `alarm:handle:remove` |
| POST | `/handle/export` | 导出 | `alarm:handle:export` |

状态：`handleStatus=0` 未处理、`1` 已处理、`2` 已确认。创建工单要求相同 `alarmId` 的处理记录为 `2`。

确认示例：

```json
{
  "alarmIds": [671599294431815216],
  "handleStatus": "2",
  "identify": "0",
  "opinion": "接口测试确认报警",
  "confirmUserId": 799001,
  "handlerName": "alarm-api-tester"
}
```

工单前置使用 `/handle/update`，随后查询：

```http
GET /handle/list?alarmId=671599294431815216&pageNum=1&pageSize=10
```

## 8. 报警工单接口 `/workorder`

| 方法 | 路径 | 用途 | 权限 |
|---|---|---|---|
| GET | `/workorder/list` | 查询 | `alarm:workorder:list` |
| GET | `/workorder/{workorderId}` | 详情 | `alarm:workorder:query` |
| POST | `/workorder` | 创建 | `alarm:workorder:add` |
| PUT | `/workorder` | 修改 | `alarm:workorder:edit` |
| PUT | `/workorder/transfer` | 转派 | `alarm:workorder:transfer` |
| PUT | `/workorder/complete` | 完成 | `alarm:workorder:complete` |
| DELETE | `/workorder/{workorderIds}` | 删除 | `alarm:workorder:remove` |

创建前置：

1. `alarmId` 对应报警存在。
2. 报警处理记录 `handleStatus=2`。
3. 命中的 Alarm 配置 `workorderConfigId>0`。
4. 工单 Push 需要 `workorderPushMessageType` 非空且存在同 messageType 的 Push 配置。

创建：

```json
{"alarmId":671599294431815216,"assigneeId":502,"assigneeName":"pete","title":"测试报警工单","content":"处理测试报警"}
```

未传 `workorderNo` 时服务生成；未传 `status` 时为 `0`；同一报警重复创建失败。成功产生 `ALARM_WORKORDER_CREATED`，顶层 `assigneeId=502`。

转派：

```json
{"workorderId":990001,"assigneeId":503,"assigneeName":"YanYan"}
```

新负责人 ID 必须为正整数。成功产生 `ALARM_WORKORDER_TRANSFERRED`；只通知新负责人，不通知旧负责人。

完成：

```json
{"workorderId":990001,"alarmId":671599294431815216,"status":"2","handleResult":"测试完成"}
```

当前完成动作不应被测试文档解释为一定产生额外 Push。

## 9. Push 配置接口 `/pushConfig`

### 9.1 接口清单

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/pushConfig/list` | 分页查询 |
| POST | `/pushConfig/export` | 导出 |
| GET | `/pushConfig/{activePushConfigId}` | 详情 |
| POST | `/pushConfig/add` | 新增 |
| POST | `/pushConfig/update` | 兼容修改入口 |
| PUT | `/pushConfig/update` | REST 修改别名 |
| DELETE | `/pushConfig/{activePushConfigIds}` | 删除 |
| POST | `/pushConfig/bindPushConfigIds` | 绑定 pushKey |
| POST | `/pushConfig/UnbindPushConfigIds` | 解绑；路径首字母按源码大写 |

当前上述权限注解均已注释，但生产仍应通过网关鉴权。

### 9.2 HTTP DEVICE 配置

```json
{
  "messageType": "10",
  "pushChannelType": "10",
  "enabled": true,
  "pushAddress": "127.0.0.1:19010/alarm-test",
  "isPassive": "0",
  "routeScope": "DEVICE",
  "configName": "TEST-HTTP-PUSH-20260718",
  "deviceSns": ["TEST-DEVICE-SN-001"]
}
```

当前 HTTP Consumer 会补 `http://`，所以 `pushAddress` 写 `host:port/path`，不要包含协议头。

### 9.3 企业微信 TENANT 配置

```json
{
  "messageType": "10",
  "pushChannelType": "20",
  "enabled": true,
  "routeScope": "TENANT",
  "recipientGroupId": 880001,
  "configName": "TEST-WECOM-PUSH-20260718",
  "deviceSns": []
}
```

校验规则：

- `routeScope` 只允许 `DEVICE`、`TENANT`；空值默认 `DEVICE`。
- 启用的 DEVICE 配置至少关联一个设备。
- 启用的 TENANT 配置不能关联设备。
- 启用的企业微信配置必须关联当前租户启用接收组。
- 禁用配置不创建有效运行 Consumer，可先保存再补全。
- 新增强制当前租户、`delFlag=0`；更新必须带 `activePushConfigId`。
- 修改推荐使用详情完整对象，避免丢失设备或通道字段。

新增接口通常只返回影响行数。用唯一 `configName` 列表回查 ID，再调用详情确认。

pushKey 绑定/解绑请求体为配置 ID 数组：

```json
[2077043211721396224,2077043211721396225]
```

## 10. 企业微信应用、用户绑定和接收组

### 10.1 应用

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/wecom/app` | 当前租户应用配置；Secret 不明文返回 |
| PUT | `/wecom/app` | 新增或更新 |

```json
{"corpId":"ww-test-corp","corpSecret":"通过安全输入提供","agentId":1000002,"enabled":true}
```

首次配置必须提供 `corpSecret`；更新时 Secret 为空表示保留。启用前还需要服务端企业微信密钥主密钥配置。

### 10.2 用户绑定

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/wecom/userBinding/list` | 当前租户绑定列表 |
| PUT | `/wecom/userBinding/batch` | 批量新增/更新 |
| DELETE | `/wecom/userBinding/{userIds}` | 按平台用户 ID 删除 |

```json
{
  "bindings": [
    {"userId":501,"wecomUserId":"XiangWenLai","enabled":true},
    {"userId":502,"wecomUserId":"pete","enabled":true},
    {"userId":503,"wecomUserId":"YanYan","enabled":true}
  ]
}
```

同一租户内平台 `userId` 和企业微信 `wecomUserId` 均须唯一；未传 `enabled` 默认启用。

### 10.3 接收组

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/recipientGroup/list` | 当前租户列表 |
| GET | `/recipientGroup/{groupId}` | 详情及 `userIds` |
| POST | `/recipientGroup` | 新增 |
| PUT | `/recipientGroup` | 修改，必须传 `groupId` |
| DELETE | `/recipientGroup/{groupIds}` | 删除 |

```json
{"groupName":"TEST-ALARM-GROUP-20260718","enabled":true,"userIds":[501,502,503]}
```

修改组会完整替换成员。接收组被 Push 配置引用时不能删除。

## 11. Session、WebSocket、HTTP 和 MQTT

### 11.1 Session

```http
POST /session/getSessionId
```

```json
{"messageType":"10"}
```

返回 SessionTicket，包含 `sessionId`、当前租户、messageType 和过期时间。普通 WebSocket 建连后首帧发送：

```json
{"sessionId":"SESSION-ID"}
```

pushKey WebSocket 首帧发送 `{"pushKey":"PUSH-KEY"}`。收到带 `deliveryId` 的消息后回执：

```json
{"ReceiptMessage":"DELIVERY-ID","messageStatus":true}
```

### 11.2 通道能力

| pushChannelType | 通道 | 目标字段 | 当前测试结论 |
|---:|---|---|---|
| 1 | WebSocket 被动/pushKey | pushKey、WS 会话 | 代码支持；需真实 Client 和回执 |
| 10 | HTTP 主动 | pushAddress | 可用；最终 HTTP 接收器判定 |
| 11 | MQTT 主动 | mqttTopic/账号/QoS | 代码支持；需真实 Broker/订阅器 |
| 20 | 企业微信内部应用 | App + recipientGroupId/assigneeId | 可用；需真实账号实收 |
| 30 | 短信 | 无有效 Consumer | 当前不作为可用能力 |
| 31 | 邮件 | 无有效 Consumer | 当前不作为可用能力 |

## 12. 专项报警接口附录

这些接口属于行业专项能力，不插入一般报警基准流程。

### 12.1 局放 `/discharge`

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/discharge/list` | 分页列表 |
| POST | `/discharge/topCount` | Top 统计 |
| POST | `/discharge/typeCount` | 检测模式统计 |
| POST | `/discharge/channelAlarmCount` | 通道统计 |
| POST | `/discharge/deviceAlarmCount` | 设备统计 |
| POST | `/discharge/alarmDPType` | 放电类型统计 |
| POST | `/discharge/deviceAlarmOfDayByCustomer` | 客户日统计 |
| POST | `/discharge/export` | 导出 |
| GET | `/discharge/{alarmId}` | 详情 |
| POST/PUT/DELETE | `/discharge`、`/discharge/{alarmIds}` | CRUD |

主模型字段：`alarmId,sensorType,channelIndex,sensorId,cycleUnit,alarmFrequency,attentionNumber,alarmNumber,maxAmplitude,alarmType,deviceSn,pdType,prpdData,tenantId,sceneType`。

### 12.2 电解槽 `/cell`

提供列表、详情、CRUD、`export`、`exportRecord`、`exportHotStatistics`、`selectAlarmListByEC`、`selectAlarmRankByPt`。其中 `selectAlarmRankByPt` 需要 `sequenceId,rowIndex,grooveNumber,observationPlace` 等 RequestParam。调用需具备电解槽业务数据和权限。

### 12.3 颜色 `/color`

提供列表、详情、CRUD、导出和 `/color/getInfoBySeq/{sequenceUid}`。字段包括 `colorId,irmsSn,color0,color1,color2,color3,bottomColor,flowColor,busBarColor,electrodesColor,voltageColor`。

## 13. 字段字典

### 13.1 AlarmConfigure

| 字段 | 类型 | 写入/只读 | 含义 |
|---|---|---|---|
| alarmConfigureId | Long | 修改必填 | 配置主键 |
| alarmConfigureName | String | 写入 | 唯一测试名称便于回查 |
| alarmType | String | 写入 | 上报报警类型匹配键 |
| deviceSn | String | 主要只读 | 关系查询中的设备 SN |
| deviceAlarmControl | String | 写入 | `0` 关闭、`1` 开启 |
| alarmConfigurePeriod | String | 写入 | `0` 全天、`1` 自定义时段 |
| alarmConfigureTimeList | Array | 写入/回读 | 自定义开始结束时间 |
| repeatAlarmDuration | Integer | 写入 | 重复报警时长 |
| repeatCycleNumber | Integer | 写入 | 重复检测周期 |
| sceneType | String | 写入 | 行业/场景匹配键 |
| tenantId | Long | 只读 | 当前登录租户 |
| deviceIds | Long[] | 写入/回读 | 当前租户设备 ID |
| deviceSet | Set<String> | 只读 | 设备 SN 关系 |
| pushEnabled | String | 写入 | `0` 不推、`1` 推 |
| pushMessageType | String | 写入 | 普通报警 Push messageType |
| workorderPushMessageType | String | 写入 | 工单 Push messageType |
| workorderConfigId | Long | 写入 | 工单模板，正数启用 |
| sequenceUid/irmsSn | String | 查询辅助 | 行业关联字段 |

### 13.2 Alarm

| 字段 | 类型 | 含义 |
|---|---|---|
| alarmId | Long | 服务生成的内部主键 |
| alarmCid | String | 外部上报 CID |
| deviceId/deviceSn | Long/String | 设备标识 |
| alarmType/alarmRank | String | 类型/级别 |
| alarmStatus | String | `-1`误报、`0`未处理、`1`已停止、`2`已处理 |
| alarmBegintime/alarmEndtime | Date | 开始/结束时间 |
| picturePath/videoPath/videoPicture | String | 媒体路径 |
| identify/opinion | String | 真伪和处理意见 |
| tenantId/sceneType | Long/String | 租户/场景 |
| irmsSn/areaSn/targetName | String | 网关、区域、目标位置 |
| maxTemp/minTemp | String | 温度扩展 |
| remarkData | String | 原始扩展备注 |

其他查询/返回字段：`alarmTime,deviceName,turnType,presentAlarmBegintime,picturePathList,videoList,alarmIds`。

### 13.3 AlarmHandle 与 Workorder

AlarmHandle 主要字段：`alarmHandleId,alarmId,workorderId,handlerId,handleUserOrder,deviceId,alarmType,alarmRank,alarmStatus,handleStatus,alarmBegintime,alarmEndtime,handleTime,identify,opinion,irmsSn,areaSn,targetName,deviceName,customerId,sceneType,handlePicture,confirmUserId,apparatusId,handlerName,alarmIds`。

Workorder 主要字段：`workorderId,workorderNo,alarmId,workorderConfigId,status,assigneeId,assigneeName,title,content,handleResult,tenantId,delFlag`。

### 13.4 ActivePushConfig

| 字段 | 类型 | 含义 |
|---|---|---|
| activePushConfigId | Long | Push 配置主键 |
| messageType | String | 与 Alarm/工单消息类型匹配 |
| pushChannelType | String | 通道枚举 |
| enabled | Boolean | 是否创建运行路由/Consumer |
| pushAddress | String | HTTP 目标地址 |
| isPassive | String | 主动/被动标志 |
| tenantId | Long | 当前租户，只读控制 |
| configName | String | 配置名 |
| pushKey | String | 被动 WS 绑定键 |
| mqttTopic/mqttUsername/mqttPassword/mqttQos | 混合 | MQTT 连接与订阅参数 |
| routeScope | String | DEVICE 或 TENANT |
| recipientGroupId | Long | 企业微信默认接收组 |
| deviceSns | String[] | DEVICE 路由设备 SN |

## 14. 枚举与状态字典

### 14.1 AlarmTypeEnums

| 值 | 当前代码描述 | 注意 |
|---:|---|---|
| 0 | 一般行业 | 历史兼容值 |
| 1 | 描述值为 `0` | 代码注释为高温，业务显示以系统字典为准 |
| 2 | 局放报警 |  |
| 3 | 集热器行业 |  |
| 4 | 回转窑行业 |  |
| 5 | 电力行业 |  |
| 6 | 描述值为 `3` | 代码注释为断线，业务显示以系统字典为准 |
| 7 | 颜色报警 |  |
| 10 | 紧急报警 | 基准示例使用 |
| 14 | 电压报警 | 电解槽 |
| 20 | 重复报警 | 枚举常量名历史上为 100 |

### 14.2 SceneTypeEnums

`1` 一般行业、`11` 维耶里、`2` 电解槽、`3` 集热器、`4` 回转窑、`5` 电力、`6` 局放。

### 14.3 处理与工单状态

- AlarmStatus：`-1` 误报、`0` 未处理、`1` 已停止、`2` 已处理。
- HandleStatus：`0` 未处理、`1` 已处理、`2` 已确认。
- Workorder 当前注释：`0` 待处理、`1` 处理中、`2` 已完成、`3` 已关闭、`4` 退回。

## 15. Alarm 到 Push 字段与路由映射

### 15.1 为什么触发或不触发

| 阶段 | 匹配/开关 | 不满足时结果 |
|---|---|---|
| Alarm 持久化 | 设备、报文、分片和业务组装有效 | 失败；alarmAdd 可能仍空 2xx，查日志 |
| 总开关 | `push.open=true` | 有 Alarm，无 push.alarm |
| Alarm 配置 | tenantId + sceneType + deviceSn + alarmType | 默认跳过 Push |
| 配置开关 | `pushEnabled=1` | 有 Alarm，无 Push |
| 消息类型 | pushMessageType 非空 | 无法命中 Push 配置 |
| Push 路由 | tenantId + messageType + DEVICE/TENANT | 无目标配置队列 |
| Push 配置 | enabled=true | 无运行 Consumer |
| 最终通道 | 地址/凭据/接收人有效 | 写失败日志或重试，不代表实收 |

### 15.2 推送到哪里

```text
DEVICE：deviceSn#messageType → activePushConfigId
TENANT：*#messageType → activePushConfigId
activePushConfigId → config.queue... → 对应 Consumer
```

同一键可以命中多个配置，因此共享环境测试前要检查同 messageType 的启用配置，避免重复推送。

### 15.3 推送给谁

企业微信接收人解析：

1. 消息顶层或 `data` 中存在 `assigneeId`：必须是 JSON 数字且大于 0。
2. 查当前租户启用用户绑定；命中后只发该企业微信 UserID。
3. 没有 `assigneeId`：查 Push 配置 `recipientGroupId`。
4. 接收组必须存在、属于当前租户且启用；逐个成员查启用绑定。
5. 未绑定成员记录失败；至少一个有效成员才有实际接收人。

常见失败码：

| 码 | 含义 |
|---|---|
| INVALID_ASSIGNEE | assigneeId 缺失有效数值或不是 JSON 数字 |
| ASSIGNEE_NOT_BOUND | 负责人没有启用企业微信绑定 |
| RECIPIENT_GROUP_REQUIRED | 普通消息配置未关联组 |
| RECIPIENT_GROUP_DISABLED_OR_MISSING | 组不存在、禁用或跨租户 |
| RECIPIENT_GROUP_EMPTY | 组没有成员 |
| NO_BOUND_RECIPIENT | 组成员均无有效绑定 |

## 16. 已知限制与停用接口

- `/alarm/alarmAdd` 吞掉业务异常并可能返回空 2xx；必须回查。
- `/alarm/export` 当前无实际导出实现。
- CID 单条停止 Service 存在，但没有独立 HTTP Controller；使用 MQ `operCode=260`。
- `SoundAlarmController` 的 `/sound_alarm`、`AlarmSendController` 的 `/send`、`AlarmSendLogController` 的 `/log` Mapping 全部注释，测试人员不要调用。
- MQTT、普通 WebSocket、pushKey WebSocket 没有真实客户端时只能标记未执行/BLOCKED。
- 短信 `30`、邮件 `31` 只有枚举，当前配置服务没有对应 Consumer。
- 示例中的用户、设备、模板和 ID 均为脱敏示例，执行时必须替换为当前租户通过合法接口取得的真实测试数据。
