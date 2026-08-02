# Alarm-Push API 接口文档

> 当前复核基线：2026-08-02。新增消息类型目录、System 字典查询和主动推送按字典值排除能力；站内推送当前不参与该过滤。

## 1. 文档用途与阅读方式

本文面向接口测试人员，描述当前 `hpis-alarm` 和 `hpis-push` 的真实外部接口、字段来源、输入输出以及 Alarm → Push 路由规则。

推荐阅读顺序：

1. 第 2～3 章了解整体链路、认证、租户和响应结构。
2. 第 4～10 章按“报警配置 → 上报 → 记录 → 处理/工单 → Push”的顺序查接口。
3. 第 13～15 章查字段、枚举以及“为什么推送、推到哪里、推给谁”。
4. 需要直接执行测试时配合本交付包的 `01-全流程测试使用手册.md`；仓库 `doc` 根目录保留同步主手册 `Alarm-Push-全流程测试使用手册.md`。

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
- 工单创建消息的 `assigneeId`为三态：`null`不推送、`0`按接收组推送、正数定向推送；转派只接受正数并只通知新目标。

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
- `workorderConfigId=0` 表示不启用工单；创建工单要求正数关联值。当前仓库没有工单模板 CRUD/表引用校验，代码只把正数作为启用门禁并复制到工单，测试不能宣称已验证模板实体存在。

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

输入字段 `alarmId` 最终保存为 `Alarm.alarmCid`；服务生成内部 Long `Alarm.alarmId`。当前 `/alarm/list` 虽接收实体字段，但 Mapper 没有按 `alarmCid` 生成过滤条件，因此下面的 `alarmCid` 只能作为客户端取回 `rows` 后的精确匹配值，不能依赖它缩小 SQL 结果集：

```http
GET /alarm/list?deviceSn=TEST-DEVICE-SN-001&pageNum=1&pageSize=200
```

在返回的 `rows` 中再以 `row.alarmCid == "TEST-ALARM-CID-20260718-HTTP"` 精确定位，不能直接取第一条。

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
| GET | `/alarm/list` | Alarm 字段 + `pageNum/pageSize` | `rows/total`；当前不按 `alarmCid`过滤，应结合设备/时间取回后在 `rows` 中精确匹配 |
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

`POST /handle/save`是报警正常处理和工单联动的唯一入口：

| 字段 | 必填性 | 规则 |
|---|---|---|
| alarmId 或 alarmIds | 必填其一 | 正整数；批量时去重；必须全部属于当前租户、正在报警且已确认 |
| opinion | 必填 | 非空处理说明；同时写 `alarm_handle.opinion`和活动工单 `handle_result` |
| handlePicture | 必填 | 非空图片地址；只写 `alarm_handle.handle_picture` |
| identify | 选填 | `0`/空按真实处理写报警状态 `2`；`1`按误报写 `-1` |
| handlerId/handlerName/tenantId/alarmStatus/handleStatus | 禁止客户端控制 | 实际处理人、租户和目标状态全部由服务端生成 |
| sceneType/irmsSn/ecHandles/apparatusId | 场景选填 | 保留现有电解槽/设备同步兼容字段；普通报警不需要 |

调用顺序固定为：先 `/handle/update`确认到 `handleStatus=2`；如需督促则创建工单；最终由任意实际处理人调用 `/handle/save`。服务端原子门禁避免已停止、已处理、未确认、跨租户或重复请求再次写入。`GET /handle/saveAll`沿用历史 Query 形式，但说明和图片同样必填并走同一事务收口。

## 8. 报警工单接口 `/workorder`

| 方法 | 路径 | 用途 | 权限 |
|---|---|---|---|
| GET | `/workorder/list` | 当前租户全部工单分页 | `alarm:workorder:list` |
| GET | `/workorder/my` | 当前租户、当前用户的工单分页 | `alarm:workorder:list` |
| GET | `/workorder/{workorderId}` | 当前租户工单详情 | `alarm:workorder:query` |
| GET | `/workorder/my/{workorderId}` | 当前用户作为定向督促目标的工单详情 | `alarm:workorder:query` |
| POST | `/workorder` | 创建 | `alarm:workorder:add` |
| PUT | `/workorder` | 只修改标题、内容 | `alarm:workorder:edit` |
| PUT | `/workorder/transfer` | 转派 | `alarm:workorder:transfer` |
| PUT | `/workorder/complete` | 兼容退役入口；固定提示改用 `/handle/save`，不更新数据 | `alarm:workorder:complete` |
| PUT | `/workorder/close` | 异常关闭 | `alarm:workorder:close` |
| DELETE | `/workorder/{workorderIds}` | 逻辑删除非终态工单 | `alarm:workorder:remove` |

创建前置：

1. `alarmId` 对应报警存在。
2. 报警处理记录 `handleStatus=2`。
3. 命中的 Alarm 配置 `workorderConfigId>0`。
4. 工单 Push 需要 `workorderPushMessageType` 非空且存在同 messageType 的 Push 配置。

### 8.1 查询边界

`/workorder/list` 和普通详情按当前租户过滤；`/workorder/my` 和 `/workorder/my/{id}` 还强制 `assignee_id=当前用户ID`。这里的“我的”表示“定向督促给我的记录”，不是报警所有权；`assigneeId=null/0`不会进入该列表，但仍可从租户全部列表查看。两个列表都返回标准分页结构 `rows + total`。

列表可传 `pageNum`、`pageSize`、`alarmId`、`workorderNo`、`status`；全部列表还可传 `assigneeId`。详情和列表批量返回 `pushTargetMode`、`alarmStatus`、`alarmEndtime`、`handleStatus`、`handlerId`、`handlerName`、`handlePicture`、`processable`和 `unprocessableReason`。分页后只按本页 `alarmId`做一次 `alarm_handle + alarm`批量查询，不逐工单循环 SQL。

### 8.2 创建

```json
{"alarmId":671599294431815216,"assigneeId":502,"assigneeName":"pete","title":"测试报警工单","content":"处理测试报警"}
```

| 字段 | 必填性 | 实际规则 |
|---|---|---|
| alarmId | 必填 | 必须是当前租户报警，且已有 `handleStatus=2` 的确认记录 |
| assigneeId | 可选 | 缺失/`null`保存 SQL `NULL`且不推送；`0`保存为组推送；正数保存为定向用户；负数拒绝 |
| assigneeName | 可选 | 展示名称；`null/0`时服务端清空；不参与处理权限判断 |
| title | 可选 | 空值由服务端生成 `报警工单-{alarmId}` |
| content | 可选 | 工单说明 |
| workorderNo | 兼容可选 | 空值由服务端生成；调用方通常不传 |
| workorderConfigId/status/tenantId/delFlag/handleResult | 禁止客户端控制 | 分别由匹配配置、服务端状态机、当前租户和生命周期生成 |

同一报警只允许一张工单。创建时服务端按该报警重新匹配 Alarm 配置并取得 `workorderConfigId`、`workorderPushMessageType`。`null`不发布创建推送事件；`0`发布组路由事件；正数发布定向路由事件。`assigneeId`是督促推送目标，不是处理权限或工单所有权。

### 8.3 通用编辑和转派

通用编辑只接受 `workorderId`、`title`、`content`：

```json
{"workorderId":990001,"title":"测试报警工单-更新","content":"更新后的处理说明"}
```

即使客户端提交 `tenantId`、`assigneeId`、`assigneeName`、`status`、`workorderConfigId`、`handleResult` 或 `delFlag`，服务也会清除这些字段；负责人和状态必须走专用接口。终态工单不能编辑。

转派：

```json
{"workorderId":990001,"assigneeId":503,"assigneeName":"YanYan"}
```

新负责人 ID 必须为正整数。成功产生 `ALARM_WORKORDER_TRANSFERRED`；只通知新负责人，不通知旧负责人。

### 8.4 报警处理联动完成

```json
{"alarmId":671599294431815216,"identify":"0","opinion":"现场复核并恢复设备","handlePicture":"/upload/alarm/2026/07/result-990001.jpg"}
```

正常处理统一调用 `POST /handle/save`。`alarmId`、非空 `opinion`、非空 `handlePicture`必填；`identify`可选，`0`表示真实处理、`1`表示误报。服务端强制当前租户，并要求 `alarm.alarm_status='0'`且 `alarm_handle.handle_status='2'`。实际处理人取登录上下文，不比较工单 `assigneeId`。

成功时同一事务写入：真实报警 `alarm_status='2'`（误报为 `-1`）、处理记录 `handle_status='1'`及处理人/说明/图片、存在的活动工单 `status='2'`和同一说明。没有工单不影响报警处理；重复处理、已停止、未确认或跨租户报警均失败。`PUT /workorder/complete`只保留兼容路由，固定返回 `{"msg":"工单完成已并入报警处理，请调用 /handle/save","code":500}`。

### 8.5 异常关闭

```json
{"workorderId":990002,"handleResult":"重复告警产生的无效工单","handlePicture":"/upload/alarm/2026/07/close-990002.jpg"}
```

`workorderId`和非空 `handleResult`必填，`handlePicture`选填。接口只依赖已有 `alarm:workorder:close` 权限，不额外识别管理员身份。它只异常关闭督促记录，不把报警伪装成已处理。设备 stop、HTTP stop、CID 过期清理等报警自然结束路径也会自动将活动工单置为 `3`并记录 `ALARM_ENDED`；已完成、已关闭和重复关闭均失败。

### 8.6 删除和状态规则

状态：`0`待处理、`1`处理中、`2`已完成、`3`已关闭、`4`退回。状态 `2/3`是终态，禁止编辑、转派、完成、关闭和删除。删除只对当前租户非终态工单设置 `del_flag=2`；批量 ID 中只要存在越租户、已删除或终态记录，事务整体回滚。

## 9. Push 配置接口 `/pushConfig`

`ActivePushConfig` 是一条“消息路由 + 单一推送通道”配置，不是包含多个通道的组合配置。一条记录只有一个 `pushChannelType`，所以 HTTP 与企业微信必须创建为两条独立配置；它们可以使用相同 `messageType`，并在同一报警上同时命中。

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
  "excludedDictValues": "2,3",
  "configName": "TEST-HTTP-PUSH-20260718",
  "deviceSns": ["TEST-DEVICE-SN-001"]
}
```

当前 HTTP Consumer 会补 `http://`，所以 `pushAddress` 写 `host:port/path`，不要包含协议头。

HTTP DEVICE 的调用方必填项为 `messageType`、`pushChannelType=10`、`enabled`、`routeScope=DEVICE`、`configName`、`pushAddress` 和至少一个 `deviceSns`。其中当前 Service 只硬校验启用的 DEVICE 必须有关联设备，尚未硬校验 `messageType`、`configName`、通道枚举和 `pushAddress`；调用方仍必须按本契约传值，否则可能出现“数据库保存成功但没有 HTTP Consumer”的配置。

### 9.3 企业微信 TENANT 配置

```json
{
  "messageType": "10",
  "pushChannelType": "20",
  "enabled": true,
  "routeScope": "TENANT",
  "excludedDictValues": "2,3",
  "recipientGroupId": 880001,
  "configName": "TEST-WECOM-PUSH-20260718",
  "deviceSns": []
}
```

企业微信 TENANT 的调用方必填项为 `messageType`、`pushChannelType=20`、`enabled`、`routeScope=TENANT`、`configName`、`recipientGroupId`，并明确传 `deviceSns=[]`。启用时 Service 会校验接收组属于当前租户且已启用，但不会在保存 Push 配置时校验企业微信应用、组成员数量和成员绑定完整性。

### 9.4 对象边界与绑定关系

```text
当前租户 tenantId
├─ 1 条企业微信应用 push_wecom_app_config
├─ N 条用户绑定 push_wecom_user_binding
│    └─ 业务 userId → 企业微信 wecomUserId
├─ N 个接收组 push_recipient_group
│    └─ N 条组成员 push_recipient_group_member(groupId, userId)
└─ N 条推送配置 active_push_config
     ├─ DEVICE → pushconfigid_devicesn(activePushConfigId, deviceSn)
     └─ 企业微信 → recipientGroupId → push_recipient_group.id
```

同一请求能否一起提交：

| 对象组合 | 是否支持 | 实际接口与说明 |
|---|---|---|
| HTTP 配置 + 企业微信配置 | 否 | 分别调用两次 `POST /pushConfig/add`；一条配置只能有一个通道 |
| 接收组 + 组成员 | 是 | `POST /recipientGroup` 同时传 `groupName`、`enabled`、`userIds` |
| 多个用户绑定 | 是 | `PUT /wecom/userBinding/batch` 的 `bindings` 可包含多名用户 |
| 用户绑定 + 接收组 | 否 | 先保存绑定，再创建/更新接收组 |
| 接收组 + ActivePushConfig | 否 | 先创建接收组取得 `groupId`，再把它作为 `recipientGroupId` 创建企业微信配置 |
| 企业微信应用 + ActivePushConfig | 否 | 先保存并读回应用，再创建企业微信配置 |

这些关系均受当前登录租户控制。请求体中的 `tenantId` 不能用于指定或切换租户；Push 配置、应用、绑定和接收组分别从安全上下文强制取得当前租户。

### 9.5 ActivePushConfig 字段契约

下表区分“调用方必须遵守”和“当前代码已强制”。“调用方必填”不等于数据库列已经设为 `NOT NULL`；历史 `active_push_config` 的多数列仍允许 NULL。

| 字段 | 类型 | 新增/修改契约 | 默认、覆盖与当前校验 |
|---|---|---|---|
| activePushConfigId | Long | 新增不传；修改必填 | 新增由服务生成；修改按当前租户校验归属 |
| messageType | String | 必填，当前可投产值最长 5 字符 | 新增时必须存在于启用的消息类型目录；修改为新编码时同样校验目录；未改编码的历史配置允许兼容读写。目录表虽允许30字符，`active_push_config.message_type` 当前仍是`CHAR(5)` |
| excludedDictValues | String | 可选，逗号分隔，最长 255 字符 | 表示“不推送”的字典值。仅目录声明支持过滤时保留；当前只允许 `alarm_rank` 中已启用的值，非过滤类型会强制保存为空字符串 |
| pushChannelType | String | 启用配置调用方必填；本章使用 `10` 或 `20` | 当前未统一拒绝未知值；未知值可保存但不会创建有效 Consumer |
| enabled | Boolean | 新增建议明确传；修改可省略 | 只有 `true` 生效；新增省略会保存为非启用状态，修改省略保留旧值 |
| routeScope | String | 可省略；只允许 `DEVICE`、`TENANT` | 新增空值默认 `DEVICE`；修改空值保留旧值；不区分大小写并统一转大写 |
| deviceSns | String[] | `enabled=true + DEVICE` 至少一个；`TENANT` 必须为空 | 新增会去空、去重；修改为 `null` 保留原关系，传 `[]` 清空全部关系 |
| pushAddress | String | 启用的 HTTP `10` 调用方必填 | 写 `host:port/path`，不要带协议；当前保存接口未硬校验，空值不会启动 HTTP Consumer |
| isPassive | String | HTTP 示例可传 `0`，当前路由不依赖此字段 | HTTP Consumer 由 `pushChannelType=10` 选择；该字段属于历史兼容字段 |
| recipientGroupId | Long | 启用的企业微信 `20` 必填；其他通道不应传 | 校验当前租户启用组；修改传 `null` 会保留旧值，当前接口不能显式清空 |
| configName | String | 调用方必填，最长 50 字符，建议当前租户内唯一 | 当前 Service 未硬校验，数据库也没有唯一键；唯一名称用于新增后回查 ID |
| tenantId | Long | 不传/只读 | 新增、查询、修改、删除均强制使用当前租户 |
| pushKey | String | 不在新增/修改中手工传 | 通过 `/bindPushConfigIds` 生成，通过 `/UnbindPushConfigIds` 解绑 |
| mqttTopic/mqttUsername/mqttPassword/mqttQos | 混合 | 仅 MQTT `11` 使用 | HTTP/企业微信不传；敏感字段不得写入测试文档和日志 |
| userId/createBy/updateBy/createTime/updateTime/delFlag | 混合 | 不传/只读 | 由登录上下文、Service 或数据库维护 |
| deviceSnGroup/groupIds | 混合 | 不传 | 当前实体中的非持久化兼容字段，本流程未使用 |

当前校验规则：

- `routeScope` 只允许 `DEVICE`、`TENANT`；空值默认 `DEVICE`。
- 启用的 DEVICE 配置至少关联一个设备。
- 启用的 TENANT 配置不能关联设备。
- 启用的企业微信配置必须关联当前租户启用接收组。
- `enabled=false` 的配置只校验 `routeScope`，其余设备、接收组和通道前置条件会跳过；可作为草稿保存，但启用前必须补全并重新读回。
- 新增强制当前租户、`delFlag=0`；更新必须带 `activePushConfigId`。
- 前端默认全选表示 `excludedDictValues=""`；用户取消某些选项时，只把未选中的字典值写入该字段，例如只接收等级 1 时写 `2,3`。
- 字典过滤只控制主动配置队列。运行开关关闭、目录不支持过滤或消息没有 `messageLevel` 时，仍按原 `deviceSn + messageType + tenantId` 路由，不做等级排除。
- 新增接口通常只返回影响行数，不返回生成的配置 ID；需要通过列表回查并调用详情确认。由于 `configName` 当前没有唯一约束，调用方必须自行使用不重复的名称。

### 9.6 新增、修改、禁用与删除流程

新增两个共存通道：

1. 按第 10 章完成企业微信应用、用户绑定和接收组；HTTP 不依赖这些对象。
2. 调用一次 `POST /pushConfig/add` 创建 HTTP DEVICE 配置。
3. 再调用一次 `POST /pushConfig/add` 创建企业微信 TENANT 配置。
4. 两条配置使用相同 `messageType`；HTTP 配置绑定设备，企业微信配置使用 `deviceSns=[]` 并关联组。
5. 分别通过 `/pushConfig/list` 和 `/pushConfig/{id}` 读回，确认字段和设备关系。
6. 检查两个配置专属 RabbitMQ 队列；启用配置应各有对应 Consumer。
7. 产生一条匹配设备和 `messageType` 的报警，分别核验 HTTP 接收结果和企业微信实收。

运行时路由会合并 `deviceSn#messageType` 和 `*#messageType` 对应的配置 ID。因此设备存在且 DEVICE 关系匹配时，HTTP 与企业微信两条配置都会收到；事件没有设备 SN 时只能命中 TENANT 配置。同一路由存在重复配置会产生多次业务投递，测试前必须查询同租户、同 `messageType` 的所有启用配置。

修改单条配置：

1. 先 `GET /pushConfig/{activePushConfigId}` 读取当前对象。
2. 修改目标字段后调用 `POST` 或 `PUT /pushConfig/update`，必须保留正确 ID。
3. 普通持久化字段传 `null` 通常表示不更新；`routeScope`、`pushChannelType`、`recipientGroupId`、`enabled` 的空值由 Service 明确保留旧值。
4. `deviceSns=null` 保留原设备；`deviceSns=[]` 删除全部设备关系。
5. 修改提交后再次读回，并检查运行队列和 Consumer。数据库事务提交后的运行态刷新失败只记录日志，不能只看 HTTP 成功响应。

不要通过把同一条 HTTP 配置改成企业微信配置来实现“双通道”。需要双通道时始终保留两条配置。当前 `recipientGroupId=null` 无法清除旧关联，通道切换可能遗留接收组引用；如必须切换，低风险方式是新建目标通道配置、验证成功后删除旧配置。

禁用和删除：

1. 修改配置为 `enabled=false`，读回并确认专属 Consumer 已停止。
2. 删除前确认不再需要该配置的历史路由；`DELETE /pushConfig/{ids}` 会删除配置及设备关系，并在事务提交后清理运行态。
3. 删除企业微信接收组前，必须先删除所有引用该组的 ActivePushConfig；即使 Push 配置已禁用，只要仍引用该组，组删除也会被拒绝。

pushKey 绑定/解绑请求体为配置 ID 数组：

```json
[2077043211721396224,2077043211721396225]
```

## 10. 企业微信应用、用户绑定和接收组

企业微信接收链路不是把企业微信 UserID 直接写进 `ActivePushConfig`。普通报警按 `recipientGroupId → userIds → 当前租户启用绑定 → wecomUserId`解析；工单消息正数 `assigneeId`定向查询绑定，`0`回退接收组，`null`时 Alarm 不发布本次工单推送。

### 10.1 应用

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/wecom/app` | 当前租户应用配置；Secret 不明文返回 |
| PUT | `/wecom/app` | 新增或更新 |

```json
{"corpId":"ww-example-corp","corpSecret":"${WECOM_CORP_SECRET}","agentId":1000002,"enabled":true}
```

| 字段 | 类型 | 必填性 | 实际规则 |
|---|---|---|---|
| corpId | String | 每次 PUT 必填 | 非空；数据库最长 64 字符 |
| corpSecret | String | 首次必填；更新可空 | 非空时使用服务端主密钥加密保存；GET 永不返回明文；更新为空保留旧 Secret |
| agentId | Long | 每次 PUT 必填 | 必须为正整数 |
| enabled | Boolean | 建议每次 PUT 明确传 | 只有 `true` 启用；省略会被保存为 `false`，不是保留旧值 |
| tenantId/id/secretConfigured | 混合 | 不传/只读 | 当前租户下每租户一条应用；GET 以 `secretConfigured` 表示是否已有 Secret |

服务端 `push.wecom.secret-key` 必须是 Base64 编码的 32 字节密钥。保存或替换 Secret 时需要它完成加密，实际发送时需要它解密；示例占位符不得原样提交，必须通过安全方式注入真实 Secret。

### 10.2 用户绑定

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/wecom/userBinding/list` | 当前租户绑定列表 |
| PUT | `/wecom/userBinding/batch` | 批量新增/更新 |
| DELETE | `/wecom/userBinding/{userIds}` | 按平台用户 ID 删除 |

```json
{
  "bindings": [
    {"userId":501,"wecomUserId":"${WECOM_USER_ID_501}","enabled":true},
    {"userId":502,"wecomUserId":"${WECOM_USER_ID_502}","enabled":true},
    {"userId":503,"wecomUserId":"${WECOM_USER_ID_503}","enabled":true}
  ]
}
```

| 字段 | 类型 | 必填性 | 实际规则 |
|---|---|---|---|
| bindings | Array | 必填且至少一项 | 同一批次事务保存；批次内 userId 或 wecomUserId 重复会整体失败 |
| bindings[].userId | Long | 必填 | 必须为正整数；表示 HPIS 业务用户 ID，不是企业微信账号 |
| bindings[].wecomUserId | String | 必填 | 非空；同一租户内只能绑定一个业务用户，数据库最长 128 字符 |
| bindings[].enabled | Boolean | 可选 | 省略默认 `true`；只有启用绑定参与接收人解析 |
| bindings[].id/tenantId | Long | 不传/只读 | Service 按当前租户和 userId 新增或更新，不使用请求中的租户切换 |

同一租户内平台 `userId` 和企业微信 `wecomUserId` 均有唯一约束。保存绑定时不会校验该 `userId` 是否真实存在于用户服务；测试必须使用当前租户通过正式用户接口取得的业务用户 ID。删除绑定不会自动把 userId 从接收组移除，组内成员会保留但投递时记录未绑定失败。

### 10.3 接收组

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/recipientGroup/list` | 当前租户列表 |
| GET | `/recipientGroup/{groupId}` | 详情及 `userIds` |
| GET | `/recipientGroup/workorderCandidates` | 按工单消息类型和设备解析候选负责人 |
| POST | `/recipientGroup` | 新增 |
| PUT | `/recipientGroup` | 修改，必须传 `groupId` |
| DELETE | `/recipientGroup/{groupIds}` | 删除 |

```json
{"groupName":"TEST-ALARM-GROUP-20260718","enabled":true,"userIds":[501,502,503]}
```

| 字段 | 类型 | 新增/修改契约 | 实际规则 |
|---|---|---|---|
| groupId | Long | POST 新增不传；PUT 修改必填 | 按当前租户校验归属；当前 Controller 未按 HTTP 方法强制，PUT 缺 ID 会变成新增，POST 带 ID 会变成修改，调用方不得依赖该兼容行为 |
| groupName | String | 新增、修改都必填 | 非空；同一租户唯一；数据库最长 100 字符 |
| enabled | Boolean | 建议明确传 | 省略默认 `true`；禁用组不能被启用的企业微信配置通过保存校验 |
| userIds | Long[] | 请求字段必填 | 每项必须为正整数，重复 ID 自动去重；修改时完整替换全部成员 |
| tenantId | Long | 不传/只读 | 强制当前租户 |

`userIds=null` 会被拒绝，但当前代码允许 `userIds=[]`。空组即使保存成功也没有实际接收人；创建启用组时调用方必须至少传一名已建立启用绑定的用户。保存接收组不会校验成员是否存在或是否已绑定企业微信，正式启用前必须通过 `/wecom/userBinding/list` 和组详情完成交叉核验。

修改组会先删除原成员，再按本次 `userIds` 完整写入，整个过程处于同一数据库事务。接收组被任意未删除 Push 配置引用时不能删除，包括已禁用但仍保留 `recipientGroupId` 的配置。

候选负责人查询：

```http
GET /recipientGroup/workorderCandidates?messageType=25&deviceSn=TEST-DEVICE-SN-001
```

`messageType`必填且非空，使用 Alarm 配置的 `workorderPushMessageType`；`deviceSn`选填。服务强制使用当前租户，只匹配 `enabled=true`、`pushChannelType=20`、同 `messageType`且接收组启用的 Push 配置：TENANT 配置无需设备即可命中，DEVICE 配置必须由 `pushconfigid_devicesn`精确关联传入设备。多个配置/组的成员按 `userId`去重，并一次批量查询成员和启用绑定。

```json
{
  "code": 200,
  "data": [
    {"userId":502,"wecomUserId":"pete","wecomReachable":true},
    {"userId":503,"wecomUserId":null,"wecomReachable":false}
  ]
}
```

未绑定成员仍作为候选推送目标返回，但 `wecomReachable=false`。该接口只辅助前端选择定向督促对象；报警处理权限和实际处理人不由 Push 或 `alarm_workorder.assignee_id`决定。

### 10.4 企业微信推荐调用顺序

1. `PUT /wecom/app`：保存应用；随后 `GET /wecom/app`，确认 `enabled=true`、`secretConfigured=true`。
2. `PUT /wecom/userBinding/batch`：建立业务 `userId → wecomUserId` 绑定；随后列表读回。
3. `POST /recipientGroup`：一个请求同时创建组和成员；使用返回的 `groupId` 调用详情确认完整成员。
4. `POST /pushConfig/add`：创建 `pushChannelType=20`、`routeScope=TENANT` 的配置并填入 `recipientGroupId`。
5. `/pushConfig/list` 回查 ID，再调用详情确认配置；检查配置专属队列存在且 Consumer 数为 1。
6. 调用 `/recipientGroup/workorderCandidates?messageType={workorderPushMessageType}&deviceSn={deviceSn}`，只从 `wecomReachable=true` 中选择正常流程负责人。
7. 普通报警不传 `assigneeId`，按配置接收组；工单创建分别验证 `null`不推送、`0`组推送和正数定向推送，转派必须传正整数且只通知新目标。

修改时也按依赖方向处理：先保证应用和绑定有效，再修改组，最后启用或修改 Push 配置。删除时反向执行：先禁用/删除引用组的 Push 配置，再删除组；应用没有删除接口，只能通过 PUT 设置 `enabled=false`。

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
| workorderConfigId | Long | 写入 | 工单模板语义的关联值，正数启用；当前不校验被引用模板实体 |
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

Workorder 持久化字段：`workorderId,workorderNo,alarmId,workorderConfigId,status,assigneeId,assigneeName,title,content,handleResult,tenantId,delFlag`。其中 `assignee_id`允许 SQL `NULL`且默认 `NULL`。`handlePicture,pushTargetMode,alarmStatus,alarmEndtime,handleStatus,handlerId,handlerName,processable,unprocessableReason`均为返回期非持久化字段。不能因为实体含有字段就认为客户端可以修改。

### 13.4 ActivePushConfig

完整的新增/修改必填性、默认值和空值语义见 9.5。本节只说明字段落点，不能代替接口契约。

| 字段 | 实体/持久化位置 | 说明 |
|---|---|---|
| activePushConfigId | `active_push_config.active_push_config_id` | Push 配置主键 |
| messageType | `active_push_config.message_type` | 与 Alarm/工单消息类型匹配 |
| pushChannelType | `active_push_config.push_channel_type` | 单一通道枚举；一条配置不能包含多个通道 |
| enabled | `active_push_config.enabled` | 是否创建有效运行路由/Consumer |
| pushAddress | `active_push_config.push_address` | HTTP 目标地址；MQTT 当前也复用为连接地址 |
| isPassive | `active_push_config.is_passive` | 历史主动/被动标志 |
| tenantId | `active_push_config.tenant_id` | 当前租户，服务端强制覆盖 |
| configName | `active_push_config.config_name` | 配置名，当前无唯一约束 |
| pushKey | `active_push_config.push_key` | 被动 WS 绑定键 |
| mqttTopic/mqttUsername/mqttPassword/mqttQos | `active_push_config.mqtt_*` | MQTT 连接与订阅参数 |
| routeScope | `active_push_config.route_scope` | DEVICE 或 TENANT |
| recipientGroupId | `active_push_config.recipient_group_id` | 企业微信默认接收组的逻辑引用 |
| deviceSns | `pushconfigid_devicesn` 关系表 | 非 `active_push_config` 列；按配置 ID 批量读写设备 SN |

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

1. 工单创建/转派消息顶层或 `data` 中存在正整数 `assigneeId`：查当前租户启用用户绑定，命中后只发该企业微信 UserID。
2. 普通报警没有 `assigneeId`，以及工单创建兼容模式明确携带数字 `0`：查 Push 配置 `recipientGroupId`。
3. 工单创建的 `assigneeId`为 SQL `NULL`时，Alarm 不发布工单事件；不能在 Push 侧把它误判成组推送。
4. 工单转派不允许 `0/null`，Alarm 在产生事件前即拒绝请求；若绕过 Alarm 构造非法转派消息，Push 返回 `INVALID_ASSIGNEE`。
5. 接收组必须存在、属于当前租户且启用；逐个成员查启用绑定。
6. 未绑定成员记录失败；至少一个有效成员才有实际接收人。

常见失败码：

| 码 | 含义 |
|---|---|
| INVALID_ASSIGNEE | assigneeId 为非数字/负数，或转派事件缺少正整数目标；创建事件的 `0`回退接收组，`null`在 Alarm 侧不发布 |
| ASSIGNEE_NOT_BOUND | 负责人没有启用企业微信绑定 |
| RECIPIENT_GROUP_REQUIRED | 普通消息配置未关联组 |
| RECIPIENT_GROUP_DISABLED_OR_MISSING | 组不存在、禁用或跨租户 |
| RECIPIENT_GROUP_EMPTY | 组没有成员 |
| NO_BOUND_RECIPIENT | 组成员均无有效绑定 |

## 16. 消息类型目录与字典过滤接口

消息组继续使用 System 字典 `push_message_group`，报警等级使用 `alarm_rank`。System 字典通过现有接口 `GET /dict/data/type/{dictType}` 维护和查询；不要直接向 Redis 写临时字典，因为 Push 读取的是 System 维护后的标准 `sys_dict2:*` 缓存结构。

### 16.1 Push 消息类型目录

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/pushMessageType/list?enabled=true` | 按消息组、messageType、启用状态查询目录 |
| GET | `/pushMessageType/{id}` | 查询单条目录元数据 |
| POST | `/pushMessageType/add` | 新增消息类型 |
| POST/PUT | `/pushMessageType/update` | 修改名称、组、过滤能力、启用状态和排序 |
| DELETE | `/pushMessageType/{ids}` | 逻辑删除；被任意 Push 配置引用时拒绝 |
| GET | `/pushMessageType/options/tree` | 前端下拉树：只返回启用且消息组字典存在的类型 |
| GET | `/pushMessageType/{messageType}/filterOptions` | 返回该类型关联字典的可选值；不支持过滤时返回空数组 |

新增/修改示例：

```json
{
  "messageGroup": "ALARM",
  "messageType": "10",
  "messageTypeName": "报警消息",
  "dictFilterSupported": true,
  "filterDictType": "alarm_rank",
  "enabled": true,
  "sortNo": 10,
  "remark": "报警主动推送"
}
```

约束如下：

- `messageGroup` 必须是启用的 `push_message_group` 字典值；消息组仅用于前端分组展示，不参与运行路由。
- `messageType` 是实际路由编码，唯一且创建后不可直接修改；已有启用 Push 配置引用时，不允许修改其字典过滤能力。当前要被`active_push_config`引用的编码必须控制在5字符内。
- 第一版只有 `filterDictType=alarm_rank` 可设为 `dictFilterSupported=true`；不支持过滤时服务端清空 `filterDictType`。
- 删除前必须先删除所有引用该 `messageType` 的 Push 配置，禁用配置也属于引用。
- 目录写事务提交后刷新内存元数据；发布后应重新查询目录，并用真实消息验证运行态。

### 16.2 过滤语义

当前 Alarm 生成 Push payload 时，`messageType`优先取匹配报警配置的`pushMessageType`；没有该值时才回退到上报`alarmType`。`alarmDegree`入库为`alarm_rank`，在发给 Push 时转成顶层`messageLevel`。配置保存的是排除集合 `excludedDictValues`：值为空表示所有等级都接收，`2,3` 表示等级 2、3 不进入该配置队列。

运行时仅在 `push.routing.dict-exclude-filter-enabled=true` 且消息类型目录支持 `alarm_rank` 时比较等级。消息无 `messageLevel` 时直接沿用原路由，因此断线、颜色等无等级报警仍按类型推送，不需要引入 `NO_VALUE`。本功能发生在主动 Push 配置队列分拣阶段，当前不改变站内推送的全量接收逻辑。元数据首次加载失败或Redis配置快照缺失时会放行，目的是避免漏报，代价是字典过滤可能短时失效。

### 16.3 前端配置顺序

1. 查询 `/pushMessageType/options/tree` 展示消息组和消息类型。
2. 用户选择类型后读取该节点的 `dictFilterSupported/filterOptions`，或单独调用 `/{messageType}/filterOptions`。
3. 支持字典时默认全选；将“未选值”按逗号拼接为 `excludedDictValues`。不支持字典时隐藏等级控件并传空字符串。
4. 保存后读取 `/pushConfig/{id}`，确认排除值已按字典顺序归一化。

## 17. 已知限制与停用接口

### 17.1 业务接口限制

- `/alarm/alarmAdd` 吞掉业务异常并可能返回空 2xx；必须回查。
- `/alarm/export` 当前无实际导出实现。
- CID 单条停止 Service 存在，但没有独立 HTTP Controller；使用 MQ `operCode=260`。
- `SoundAlarmController` 的 `/sound_alarm`、`AlarmSendController` 的 `/send`、`AlarmSendLogController` 的 `/log` Mapping 全部注释，测试人员不要调用。
- MQTT、普通 WebSocket、pushKey WebSocket 没有真实客户端时只能标记未执行/BLOCKED。
- 短信 `30`、邮件 `31` 只有枚举，当前配置服务没有对应 Consumer。
- 示例中的用户、设备、模板和 ID 均为脱敏示例，执行时必须替换为当前租户通过合法接口取得的真实测试数据。

### 17.2 Push 接口—实体—Schema 契约扫描结果

本节是 2026-08-02 按当前 Controller、请求对象、Service、Mapper、增量 SQL 和测试库结构进行的复核结果。除工单闭环外，当前版本已增加消息类型目录、配置排除值以及运行时字典过滤；数据库迁移和真实服务结果以本交付包最新执行报告为准。

已闭合的 Schema 问题：正式 `alarm_configure` 不含 `device_sn`，设备关系只存在于 `alarm_device_configure.device_sn`。当前基础列表 SQL 不再选择主表 `device_sn`；按设备筛选使用关系表 `EXISTS`；报警配置匹配从关系表读取并别名为 `device_sn`。基础详情/修改使用的 ResultMap 也不再读取 `device_sn`，仅明确带设备关系列的查询使用设备 ResultMap。`AlarmWorkorderMapperXmlContractTest` 已增加防回归断言，真实服务的配置详情和修改已通过，避免再次出现 `Unknown column 'device_sn'`或 ShardingSphere 读取缺失列异常。

| 级别 | 已确认遗漏/差异 | 实际影响 | 当前使用约束 |
|---|---|---|---|
| 高 | ActivePushConfig 已校验 `messageType` 目录和排除字典值，但仍未统一硬校验 `pushChannelType`、`configName`，HTTP 未硬校验 `pushAddress` | 可能保存成功但无法创建有效 Consumer | 调用方按 9.5 必填；启用后必须检查详情、队列和 Consumer |
| 高 | `recipientGroupId=null` 在更新中表示保留旧值，Mapper 也跳过 NULL | 无法通过现有更新接口清空旧组；通道切换可留下引用并阻止删组 | 不原地切换通道；新建目标配置、验证后删除旧配置 |
| 高 | 数据库事务提交后才刷新 Redis 路由和 RabbitMQ Consumer，刷新异常只写日志 | API 成功不保证运行态已经生效 | 每次启用/修改后做运行态核验；重启或重新保存可补偿 |
| 高 | `/pushConfig/list` 和详情直接返回持久化实体，实体中的 `mqttPassword` 没有响应脱敏注解 | MQTT 配置密码可能通过查询接口明文返回；企业微信 Secret 已使用专用 View 脱敏，不受此项影响 | MQTT 上线前必须单独整改响应 DTO/脱敏；当前不要在共享环境查询、截图或导出真实 MQTT 密码 |
| 中 | 企业微信 Push 配置保存时只校验组存在且启用，不校验 App、空组和成员绑定 | 错误延迟到实际投递，产生失败日志但没有实收 | 启用前依次读回 App、绑定、组成员和配置 |
| 中 | Push 配置权限注解已注释，企业微信三个 Controller 也没有方法级权限注解 | 直连微服务时主要依赖上游认证头和租户上下文，没有细粒度权限门禁 | 正式环境只允许通过网关和合法 Token；不得向公网或非受控网络暴露微服务端口 |
| 中 | 接收组 POST/PUT 共用 `saveGroup`，由 `groupId` 是否为空决定新增/修改 | PUT 漏传 ID 会新增；POST 错传 ID 会修改 | 严格遵守 POST 不传 ID、PUT 必传 ID，并在写后读回 |
| 中 | `userIds=[]` 被代码和单元测试明确允许 | 启用空组可保存，但普通报警没有接收人 | 空组仅用于禁用草稿或失败检测；生产启用组至少一名有效绑定用户 |
| 中 | 删除用户绑定不检查其是否仍属于接收组 | 组成员关系保留，后续投递记录未绑定失败 | 删除绑定前查询并调整相关接收组 |
| 中 | ActivePushConfig 新增不返回生成 ID，`configName` 又没有唯一键 | 请求超时或重复提交后难以唯一回查，重复配置可能造成重复推送 | 配置名由调用方保证租户内唯一；新增前后查询，禁止无条件重试 |
| 中 | 正式资源中有企业微信和消息字典过滤增量 SQL，但没有完整 Push 基础库 DDL | 不能从空库仅靠仓库 SQL 完成正式安装 | 全新环境先导入正式 Push 基础库，再按顺序执行两个增量和结构预检 |
| 中 | `push_message_type_catalog.message_type` 允许30字符，`active_push_config.message_type` 仍是`CHAR(5)`，两个Service也没有统一长度校验 | 可以建立“目录可保存但Push配置写入失败”的长编码 | 当前投产编码限制5字符以内；后续单独统一Schema和DTO校验 |
| 中 | `20260716_wecom_push_incremental.sql` 的建表、加列和加索引不是整体幂等 | 已执行环境盲目重跑会在对象已存在处失败 | 执行前查 `information_schema`；按实际状态逐项处理，不能依赖 DDL 整体回滚 |
| 中 | 历史 `active_push_config.enabled` 是可空 `CHAR(5)`，实体是 Boolean；`mqtt_qos` 是 VARCHAR，实体是 Integer | 依赖 MyBatis/MySQL 隐式转换，非法历史值没有 Schema 保护 | 只通过接口写 `true/false` 和整数 QoS；上线前只读扫描异常值 |
| 中 | `pushconfigid_devicesn` 没有主键、唯一键或外键 | 手工 SQL、历史数据或并发异常可产生重复/孤儿关系 | 配置关系只走接口；结构核验时检查重复 `(active_push_config_id,device_sn)` 和孤儿行 |
| 中 | Mapper 使用 `pushConfigId_deviceSn`，真实结构快照表名是 `pushconfigid_devicesn` | 在大小写敏感的 MySQL/Linux 配置上可能找不到表 | 部署前核对 `lower_case_table_names` 和实际表名；不要另建大小写不同的重复表 |
| 中 | `/alarm/list` 实体含 `alarmCid`，但当前分页 QueryWrapper 没有按该字段过滤 | 传 `alarmCid` 看似合法却不会缩小结果，自动化可能抓错内部 ID | 先按当前租户、设备和时间分页，再在响应 `rows` 中精确匹配 `alarmCid`；不能只取第一条 |
| 中 | 工单创建/编辑/转派仍直接接收持久化实体，仅完成/关闭使用专用请求 DTO | 客户端可提交多余字段，边界依赖 Service 清除和服务端重查 | 严格按第 8 章请求字段；自动化必须验证多余的租户、状态、负责人字段不能越权生效 |
| 中 | 候选负责人接口只检查 Push 企业微信配置、组和绑定，不校验业务用户是否仍存在/启用 | 可返回历史 userId，或返回 `wecomReachable=false` | 前端最终选人仍应结合用户目录；Alarm 保存正数负责人但不调用 Push 同步校验 |
| 中 | `workorderConfigId` 只有字段、正数门禁和工单快照，仓库内没有对应模板表/Controller/Mapper 查询 | 任意正数都能通过当前创建门禁，不能证明模板真实存在 | 把它视为当前兼容关联值；模板功能落地前不要在测试报告写“模板内容已应用” |
| 低 | 企业微信四张新表含 `del_flag`，候选查询已过滤配置/组/成员，现有应用、绑定、组 CRUD 仍以物理删除为主且部分列表不统一过滤逻辑删除 | 当前 API 物理删除数据正常；手工把 `del_flag=2` 可能在部分列表继续可见 | 继续只走接口删除；不要手工改逻辑删除标记，后续若统一软删除需同时修改全部 Mapper |
| 低 | ActivePushConfig Controller 直接接收持久化实体，没有独立新增/修改 DTO 或 Bean Validation | 可写/只读边界主要靠 Service 和本文约定，字段长度错误多由数据库返回 | 调用方不得提交只读字段；后续低风险迭代再补 DTO/校验，不在本轮改接口 |

### 17.3 当前测试证据边界

历史 78/82 请求结果仅作为旧契约证据。当前 Collection 为 87 个请求，新增目录、字典和等级过滤断言；是否通过必须以本轮重新执行的 Maven、迁移检查及真实服务报告为准，不能复用历史数字。

企业微信客户端到达能力已由当前测试方确认，可作为状态矩阵的通道基线；但每条当前事件仍须存在正确目标和成功发送日志，失败记录只能证明执行了发送尝试。当前测试库未执行 DDL，`idx_alarm_workorder_tenant_assignee_status`只读检查为不存在，正式发布前必须按第 3 号文档迁移并复核。以下字段和异常恢复契约仍应在部署环境的专项负向回归中保留证据：

- `messageType` 缺失或超过 5 字符；
- `pushChannelType` 缺失或未知；
- HTTP `pushAddress` 缺失、带协议头或不可达；
- `configName` 缺失、超过 50 字符或重复；
- PUT 接收组漏传 `groupId`、POST 错传 `groupId`；
- 空组、未绑定成员、删除仍在组内的绑定；
- 更新显式清空 `recipientGroupId`；
- ActivePushConfig 新增超时重试导致的重复配置；
- 数据库写成功但 after-commit 运行态刷新失败。

因此，“本地真实服务端闭环已验证”不等于“企业微信客户端实收、生产网关鉴权、生产迁移和所有字段负向契约均已验证”。后续回归应把上述项目作为独立用例，并同时断言 HTTP 业务码、数据库状态、Redis 路由、RabbitMQ 队列/Consumer 和最终投递日志。
