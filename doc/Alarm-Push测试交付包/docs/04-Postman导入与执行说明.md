# Postman 导入与执行说明

## 1. 使用范围

本文说明如何把交付包中的 Alarm-Push Collection 和 Environment 导入 Postman，并按正确顺序执行 HTTP 报警 `messageType=10`、工单 `messageType=25`、失败场景和清理请求。

企业微信应用、用户绑定、接收组和收件人路由不在该 Collection 中，必须按[全流程测试使用手册](01-全流程测试使用手册.md)人工执行。

## 2. 导入文件

依次导入：

1. [hpis-alarm-push.postman_collection.json](../postman/hpis-alarm-push.postman_collection.json)
2. [hpis-alarm-push.postman_environment.json](../postman/hpis-alarm-push.postman_environment.json)

在 Postman 中点击 `Import`，选择文件后确认导入。完成后应看到：

- Collection：`HPIS Alarm Push API - Fresh Environment`
- Environment：`HPIS Alarm Push - Fresh Environment`

执行请求前，在右上角环境选择器中选中 `HPIS Alarm Push - Fresh Environment`。如果显示 `No environment`，变量不会生效。

Apifox 可导入 Postman Collection v2.1 和 Environment；导入后仍需逐项核对变量、鉴权 Header 和测试脚本兼容性。

## 3. 环境变量怎么填写

| 变量 | 示例默认值 | 必须设置为 | 来源 |
|---|---|---|---|
| `alarmBaseUrl` | `http://127.0.0.1:18806` | 当前环境 Alarm 网关或直连地址，不要以 `/` 结尾 | 部署人员或网关配置 |
| `pushBaseUrl` | `http://127.0.0.1:8812` | 当前环境 Push 网关或直连地址，不要以 `/` 结尾 | 部署人员或网关配置 |
| `receiver10BaseUrl` | `http://127.0.0.1:19010` | 普通报警 HTTP 接收器地址 | 本地接收器或测试回调服务 |
| `receiver25BaseUrl` | `http://127.0.0.1:19025` | 工单 HTTP 接收器地址 | 本地接收器或测试回调服务 |
| `token` | `replace-with-valid-token` | 当前测试租户有效 Token，只保存在本机环境变量 | 测试账号登录结果 |
| `tenantId` | `990010` | 当前测试租户 ID | Token 对应租户信息 |
| `userId` | `990010` | 当前测试用户 ID，同时作为工单负责人 | 用户中心或当前登录信息 |
| `deviceAId` | `990101` | 当前租户测试设备 A 的 Long ID | 设备列表接口或设备管理页面 |
| `deviceASn` | `CODX-EMERG-10-DEV-A` | 设备 A 的真实 SN | 设备列表接口或设备管理页面 |
| `deviceAGatewaySn` | `CODX-EMERG-10-GW-A` | 设备 A 的真实网关 SN | 设备详情或网关信息 |
| `deviceBId` | `990102` | 当前租户测试设备 B 的 Long ID | 设备列表接口或设备管理页面 |
| `deviceBSn` | `CODX-EMERG-10-DEV-B` | 设备 B 的真实 SN | 设备列表接口或设备管理页面 |
| `deviceBGatewaySn` | `CODX-EMERG-10-GW-B` | 设备 B 的真实网关 SN | 设备详情或网关信息 |
| `normalDeviceId` | `990103` | 当前租户普通报警负例设备的 Long ID | 设备列表接口或设备管理页面 |
| `normalDeviceSn` | `CODX-EMERG-10-NORMAL-DEV` | 普通报警负例设备的真实 SN | 设备列表接口或设备管理页面 |
| `workorderConfigId` | `900` | 当前环境有效且大于 0 的工单模板 ID | 工单模板管理或数据库只读查询 |
| `runId` | 空 | 本轮唯一标识；留空时 Collection 自动生成时间字符串 | Collection 根级 pre-request script |
| `internalAlarmId` | 空 | 设备 A 本轮报警入库后生成的内部 Long `alarmId` | `/alarm/list?alarmCid=...` 或证据 SQL |
| `emergencyAlarmConfigureId` | 空 | Collection 列表请求自动回填 | `01` 文件夹测试脚本 |
| `normalAlarmConfigureId` | 空 | Collection 列表请求自动回填 | `01` 文件夹测试脚本 |
| `pushConfig10Id` | 空 | Collection 列表请求自动回填 | `02` 文件夹测试脚本 |
| `pushConfig25Id` | 空 | Collection 列表请求自动回填 | `02` 文件夹测试脚本 |

注意：

- Token、密码和企业微信 Secret 不得提交回仓库，也不要截图传播。
- 三个设备必须属于 `tenantId` 对应租户；不能只修改 SN 而保留其他租户的设备 ID。
- 重跑前建议清空 `runId` 和五个运行期 ID，避免复用上一轮数据。
- 请求体中的 `tenantId` 不是租户隔离的可信来源，服务端仍以当前 Token 上下文为准。

## 4. 启动本地 HTTP 接收器

仓库提供双端口测试接收器：

[start-alarm-push-http-receiver.ps1](../../../src/test/resources/scripts/start-alarm-push-http-receiver.ps1)

从 `hpis-alarm` 仓库根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File '.\src\test\resources\scripts\start-alarm-push-http-receiver.ps1' `
  -Ports '19010,19025'
```

看到 `Alarm push HTTP receiver started` 后保持窗口运行。另开终端检查：

```powershell
Invoke-RestMethod 'http://127.0.0.1:19010/_events'
Invoke-RestMethod 'http://127.0.0.1:19025/_events'
```

两个接口都应返回 `count` 和 `events`。远程测试环境不能访问本机 `127.0.0.1` 时，应使用 Alarm/Push 服务实际可访问的测试回调地址，并同步修改两个 receiver 变量和 Push 配置目标。

## 5. 执行前检查

开始运行 Collection 前逐项确认：

- [ ] Push 服务已启动并能连接 `hpis_push`、Redis 和 RabbitMQ。
- [ ] Alarm 服务已启动并能连接 `hpis_alarm`、设备服务、Redis 和 RabbitMQ。
- [ ] `alarm_queue`、`push.alarm` 及动态队列具备声明和消费权限。
- [ ] 当前 Token 有 Alarm 配置、报警、处理、工单和 Push 配置权限。
- [ ] 三个设备 ID/SN/网关 SN 属于当前租户。
- [ ] `workorderConfigId` 对应模板真实存在。
- [ ] 19010、19025 接收器或替代回调地址可由 Push 服务访问。
- [ ] 已在测试库执行[环境预检 SQL](../sql/alarm-push-api-setup.sql)，阻断项均已处理。
- [ ] 不存在上一轮同名 Alarm/Push 配置；如存在先通过 API 清理。

## 6. Collection 执行顺序

不要整包无确认连续运行。按文件夹顺序执行，每个阶段检查测试断言后再继续。

### 6.1 `00 - Receiver`

清空 19010 和 19025 的历史事件。两个请求都应返回 HTTP 200，事件计数归零。

### 6.2 `01 - Alarm Configure`

依次创建紧急报警配置和普通报警负例配置，再查询列表。列表请求根据配置名称自动回填：

- `emergencyAlarmConfigureId`
- `normalAlarmConfigureId`

最后执行详情请求，确认紧急配置的 `pushMessageType=10`、`workorderPushMessageType=25`，设备关系与当前环境变量一致。

### 6.3 `02 - Push Config`

分别创建 `messageType=10` 和 `messageType=25` 的 HTTP Push 配置，再执行列表请求自动回填：

- `pushConfig10Id`
- `pushConfig25Id`

详情请求应确认 25 配置已启用，并指向 19025 工单接收器。

### 6.4 `03 - Alarm messageType 10`

创建设备 A、设备 B 两条紧急报警，并创建一条普通报警负例。每个 `POST /alarm/alarmAdd` 返回空 HTTP 2xx 只表示 Controller 调用结束，不代表报警已经入库或 Push 成功。

必须同时确认：

1. 设备 A、B 的外部报警 ID 为 `API-PUSH-E2E-A-{runId}`、`API-PUSH-E2E-B-{runId}`。
2. 19010 接收器收到了设备 A、B 的 `messageType=10`。
3. 普通报警负例设备没有进入 19010。
4. 使用 `/alarm/list?alarmCid=API-PUSH-E2E-A-{runId}` 回查内部 `alarmId`。

将设备 A 的内部 Long `alarmId` 写入环境变量 `internalAlarmId`。也可执行[测试证据查询 SQL](../sql/alarm-push-api-check.sql)，按 `alarm_cid` 找到内部 ID 后回填。

### 6.5 `04 - Workorder messageType 25`

确认 `internalAlarmId` 已填写后再运行。该阶段依次：

1. 将报警处理状态确认到 `handleStatus=2`。
2. 创建工单并指定 `userId` 为负责人。
3. 验证 19025 收到 `ALARM_WORKORDER_CREATED`。
4. 验证 19010 没有收到工单事件。
5. 重复创建同一报警工单，预期业务失败。
6. 查询工单，预期只保留一条。

### 6.6 `05 - Negative Config and Failure`

按请求名称逐个执行并观察断言：禁用 25 配置、恢复配置、设置不可达地址、恢复地址、清空 `workorderPushMessageType`、恢复字段。每个负例结束后必须执行相邻恢复请求，不能把失败配置留给后续测试。

### 6.7 `06 - Cleanup`

删除本轮两个 Push 配置和两个 Alarm 配置，再清空两个接收器。删除后分别回查列表或详情，确认配置不可见。

Collection 的清理只删除配置和接收器事件，不代替运行数据清理 SQL。

## 7. 结果判定

| 检查点 | PASS | FAIL |
|---|---|---|
| 配置接口 | 新增后能回查、修改生效、删除后不可见 | 响应成功但回查不到，或字段/设备关系错误 |
| 报警上传 | 能按外部 CID 查到唯一内部 Long ID | 只有 HTTP 2xx，数据库没有记录或出现重复记录 |
| 普通报警 Push | 19010 收到设备 A/B 的 10，负例未收到 | 目标设备漏推、负例误推或通道错误 |
| 工单 Push | 19025 收到 CREATED，19010 未收到 | 未推送、推错通道、重复创建仍成功 |
| 失败场景 | 失败符合预期且恢复请求成功 | 失败场景误成功，或恢复后链路仍不可用 |
| 清理 | 配置、接收器事件和授权清理的数据均无残留 | 仍有本轮数据或误删其他测试数据 |

依赖服务、权限、设备、模板或回调地址不可用时标记 BLOCKED，并记录具体依赖，不要写成 FAIL 或 PASS。

## 8. 企业微信测试边界

Postman Collection 不包含以下接口和断言：

- `/wecom/app` 企业微信应用配置；
- `/wecom/userBinding/*` 平台用户与企业微信用户绑定；
- `/recipientGroup/*` 接收组增删改查；
- 普通报警向接收组成员推送；
- 工单创建向负责人推送；
- 工单转派只向新负责人推送。

这些步骤必须按照全流程测试手册的企业微信章节执行。最终证据至少包括企业微信收件截图或消息标识、平台用户 ID、企业微信用户 ID、接收组成员、工单负责人和转派前后负责人。只有真实目标人员收到消息，企业微信链路才可判定 PASS。
