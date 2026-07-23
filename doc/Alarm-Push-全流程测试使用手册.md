# Alarm → Push 全流程测试使用手册

## 1. 先看这里：本手册能完成什么

这是一份可以照着执行的测试手册。即使你不了解报警服务、RabbitMQ 或 Push 服务，也可以按顺序完成：

```text
准备账号和设备
→ 创建 Push 配置
→ 创建报警配置
→ 上报报警
→ 查询报警记录
→ 查看/修改/停止/删除报警
→ 验证最终推送
→ 确认报警并创建工单
→ 验证工单发给负责人
→ 清理本轮数据
```

第一次执行只走第 5～7 章“HTTP 基准流程”。它不需要企业微信凭据，可以证明 Alarm → Push 技术链路完整。需要验证“推给谁”时再走第 8 章企业微信流程。

字段含义、完整接口清单和响应结构查同一交付包中的 `02-API接口文档.md`。

结果定义：

- `PASS`：输入、业务记录、消息路由和最终接收都符合预期。
- `FAIL`：环境满足，但实际行为与预期不一致。
- `BLOCKED`：缺服务、权限、设备、模板、凭据或人工接收确认，无法继续。

不要把 HTTP 200、RabbitMQ ACK 或一条发送日志单独判定为推送成功。

本轮工单迭代不强制重复验证企业微信客户端到达：服务端闭环以 MQ 消费、配置路由、候选/接收人解析、发送尝试和 `push_message_log`为证据；报告中标记“企业微信客户端未复测”。只有要声明“客户端到达 PASS”时，才必须再提供目标账号实收和禁收账号未收证据。

## 2. 整体流程和成功画面

完整成功时应看到：

1. Alarm 配置详情包含正确设备、场景、报警类型和 Push messageType。
2. Push 配置详情处于启用状态，并指向预期 HTTP 地址或企业微信接收组。
3. 上报后 `/alarm/list` 返回的 `rows` 中能按外部 CID 精确匹配记录，并取得内部 Long `alarmId`；当前 SQL 不使用 `alarmCid` 请求参数过滤。
4. `/alarm/query/{alarmId}` 返回详情。
5. Push 命中预期 `activePushConfigId`，最终 HTTP 接收器或企业微信收到消息。
6. 普通企业微信报警由接收组成员收到，组外人员不收到。
7. 工单创建只通知负责人；转派只通知新负责人。
8. 停止后 `alarmEndtime` 非空，重复停止不产生第二条活跃报警。
9. 删除后本轮配置、报警和工单不可见；修改过的原配置已经恢复。

## 3. 最小术语表

| 术语 | 测试人员需要知道的含义 |
|---|---|
| tenantId | 当前登录用户所属租户；决定数据隔离，不能靠请求体切换 |
| deviceId | 设备数据库 ID；创建 Alarm 配置时使用 |
| deviceSn | 设备序列号；报警上报和 DEVICE Push 路由使用 |
| gatewaySn/irmsSn | 网关或 IRMS 序列号；停止和行业逻辑可能使用 |
| alarmConfigureId | Alarm 配置主键 |
| alarmCid | 外部上报 JSON 的 `alarmId`，字符串；用于追踪一次报警 |
| alarmId | Alarm 服务生成的内部 Long 主键；详情、处理、工单、删除使用 |
| messageType | Alarm 消息与 Push 配置之间的匹配类型 |
| pushChannelType | 最终通道：HTTP、MQTT、WebSocket、企业微信等 |
| routeScope | `DEVICE` 按设备路由；`TENANT` 对当前租户所有设备生效 |
| activePushConfigId | Push 配置主键，也用于动态队列和日志定位 |
| recipientGroupId | 普通企业微信消息的接收组 |
| assigneeId | 工单当前负责人平台用户 ID；存在时覆盖接收组 |
| Consumer | Push 为某条启用配置建立的最终通道消费者 |
| runId | 本轮唯一时间串，用来保证只查询和清理本轮数据 |

最容易混淆的是：外部请求字段 `alarmId` 是字符串 CID；查询出来的 `rows[0].alarmId` 才是内部 Long ID。

## 4. 测试范围与不支持能力

| 能力 | 本手册结论 |
|---|---|
| Alarm 配置 CRUD | 必测 |
| Alarm 记录 CRUD、图片、统计 | 必测；图片/统计需相应数据 |
| HTTP 与 `alarm_queue` 上报 | 必测 |
| 按 CID、设备、IRMS 停止 | 按环境选择；CID 外部停止走 MQ |
| HTTP 主动 Push | 基准必测 |
| 企业微信接收组和负责人 | 有真实凭据与账号时必测，否则 BLOCKED |
| MQTT | 有真实 Broker/订阅器时执行 |
| 普通 WS、pushKey WS | 有真实 Client 时执行 |
| 短信、邮件 | 当前没有实际 Consumer，不测试为可用 |
| `/alarm/export` | Controller 当前无实际实现，不判 PASS |

开始前先确认你能从本文回答以下问题；不能回答时点击对应章节继续阅读，不要猜字段：

| 问题 | 答案位置 |
|---|---|
| 我先启动/检查什么？ | 第 5 章环境与权限检查 |
| deviceId 和 deviceSn 从哪里取得？ | 第 5.3 节设备列表 |
| 为什么 Alarm 与 Push 的 messageType 要一致？ | 第 3 章术语、第 7 章配置步骤 |
| alarmCid 和内部 alarmId 有什么区别？ | 第 3 章、A-05 |
| alarmAdd 为什么 2xx 仍可能失败？ | 第 1 章、A-05、第 15 章 |
| 普通报警最终推到哪里、给谁？ | A-06 和第 8.4 节 |
| 工单为什么只给负责人？ | 第 8.6～8.7 节 |
| 失败后先查哪一层？ | 第 15 章 |
| 测试完成按什么顺序清理？ | 第 17 章 |

## 5. 环境、权限和账号检查

### 5.1 依赖清单

| 项目 | 默认值/要求 | 失败结论 |
|---|---|---|
| hpis-alarm | `127.0.0.1:8806` | BLOCKED-ALARM |
| hpis-push | `127.0.0.1:8812` | BLOCKED-PUSH |
| hpis-device | `127.0.0.1:8805` | 无法选择设备 |
| Nacos | `127.0.0.1:8848` | 服务配置/注册阻断 |
| MySQL | hpis_alarm、hpis_push、hpis_system | 数据阻断 |
| Redis | Alarm 设备缓存、Push 路由缓存可用 | 配置或路由阻断 |
| RabbitMQ | AMQP 5672、管理端 15672 | MQ 链路阻断 |
| HTTP 接收器 | 19010 | 最终 HTTP 验证阻断 |
| 企业微信 | App 可见、三个测试账号可确认 | 第 8 章 BLOCKED |

### 5.2 权限探活

先准备合法 Token，再执行第 6.1 节变量脚本。随后：

```powershell
Invoke-RestMethod -Method Get -Uri "$AlarmBaseUrl/configure/list?pageNum=1&pageSize=1" -Headers $Headers
Invoke-RestMethod -Method Get -Uri "$AlarmBaseUrl/alarm/list?pageNum=1&pageSize=1" -Headers $Headers
Invoke-RestMethod -Method Get -Uri "$AlarmBaseUrl/workorder/list" -Headers $Headers
Invoke-RestMethod -Method Get -Uri "$PushBaseUrl/pushConfig/list?pageNum=1&pageSize=1" -Headers $Headers
Invoke-RestMethod -Method Get -Uri "$PushBaseUrl/recipientGroup/list" -Headers $Headers
```

判定：

- 200：继续。
- 401：Token 无效或过期。
- 403：缺权限，标记 BLOCKED，找管理员授权。
- 连接失败：对应服务未启动或地址错误。
- 不允许用 SQL 写入绕过接口权限。

### 5.3 选择真实测试设备

```powershell
$DeviceRows = (Invoke-RestMethod -Method Get `
  -Uri "$DeviceBaseUrl/basicInfo/pdList?pageNum=1&pageSize=20" `
  -Headers $Headers).rows
$DeviceRows | Select-Object deviceId,deviceSn,deviceName,tenantId
```

选择一台属于当前租户、允许产生测试报警的设备：

```powershell
$DeviceId = [long](Read-Host '输入上面选中的 deviceId')
$DeviceSn = Read-Host '输入同一设备的 deviceSn'
$GatewaySn = Read-Host '输入该设备对应 gatewaySn/irmsSn；无网关测试值时咨询设备负责人'
```

如果列表为空、设备 SN 为空或设备不属于当前租户，停止并标记 `BLOCKED-DEVICE`，不要编造设备。

## 6. 本轮测试变量与数据来源

### 6.1 初始化变量

```powershell
$AlarmBaseUrl = 'http://127.0.0.1:8806'
$PushBaseUrl = 'http://127.0.0.1:8812'
$DeviceBaseUrl = 'http://127.0.0.1:8805'
$ReceiverBaseUrl = 'http://127.0.0.1:19010'
$RabbitManagementUrl = 'http://127.0.0.1:15672'
$RunId = Get-Date -Format 'yyyyMMddHHmmss'
$Token = Read-Host '输入本轮测试 Bearer Token'
$CurrentUserId = Read-Host '输入当前测试用户 ID'
$CurrentUsername = Read-Host '输入当前测试用户名'
$Headers = @{
  Authorization = "Bearer $Token"
  user_id = "$CurrentUserId"
  username = "$CurrentUsername"
}
```

工单转派后完成和异常关闭还需要同租户独立账号，按实际网关要求构造：

```powershell
$SecondAssigneeToken = Read-Host '输入第二负责人 Token'
$SecondAssigneeHeaders = @{ Authorization = "Bearer $SecondAssigneeToken" }
$ClosePermissionToken = Read-Host '输入具备 alarm:workorder:close 权限的 Token'
$ClosePermissionHeaders = @{ Authorization = "Bearer $ClosePermissionToken" }
```

不要把 `$Token` 或企业微信 Secret 输出到文件、截图或报告。

### 6.2 动态 ID 从哪里来

| 变量 | 来源 |
|---|---|
| `$DeviceId/$DeviceSn` | `/basicInfo/pdList` 选择同一行 |
| `$PushConfigId` | 新增后按唯一 `configName` 查询 `/pushConfig/list` |
| `$AlarmConfigureId` | 新增后按唯一 `alarmConfigureName` 查询 `/configure/list` |
| `$AlarmId` | 上报后用 `/alarm/list?deviceSn=...` 查询，再在响应 `rows` 中精确匹配 `$AlarmCid`；当前接口不按 alarmCid 过滤 |
| `$GroupId` | 创建组后 `/recipientGroup/list` 按唯一组名选择 |
| `$WorkorderId` | 创建工单后 `/workorder/list?alarmId=$AlarmId` |

任何 ID 没有取到时不要继续。先查第 15 章。

## 7. 基准流程 A：HTTP 最终接收闭环

### A-01 启动并自检 HTTP 接收器

先执行脚本自检：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File hpis-alarm\src\test\resources\scripts\verify-alarm-push-http-receiver.ps1
```

预期输出：`Alarm push HTTP receiver self-test passed.`

再单独打开一个 PowerShell 窗口运行接收器：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File hpis-alarm\src\test\resources\scripts\start-alarm-push-http-receiver.ps1 `
  -Ports 19010
```

当前窗口确认：

```powershell
Invoke-RestMethod -Method Delete -Uri "$ReceiverBaseUrl/_events"
Invoke-RestMethod -Method Get -Uri "$ReceiverBaseUrl/_events"
```

预期 `count=0`。端口占用时先确认占用进程，不要误停 Alarm、Push、Nacos、RabbitMQ 或 MySQL。

### A-02 新增禁用的 HTTP Push 配置

```powershell
$PushConfigName = "TEST-HTTP-PUSH-$RunId"
$PushBody = @{
  messageType = '10'
  pushChannelType = '10'
  enabled = $false
  pushAddress = '127.0.0.1:19010/alarm-test'
  isPassive = '0'
  routeScope = 'DEVICE'
  configName = $PushConfigName
  deviceSns = @($DeviceSn)
} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$PushBaseUrl/pushConfig/add" `
  -Headers $Headers -ContentType 'application/json' -Body $PushBody
```

回查：

```powershell
$PushRows = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/pushConfig/list?pageNum=1&pageSize=20&configName=$PushConfigName" `
  -Headers $Headers).rows
$PushConfig = $PushRows | Where-Object { $_.configName -eq $PushConfigName } | Select-Object -First 1
$PushConfigId = [long]$PushConfig.activePushConfigId
if ($PushConfigId -le 0) { throw '未回查到 PushConfigId' }
```

预期：当前租户、`enabled=false`、设备 SN 和 HTTP 地址正确。

### A-03 新增 Alarm 配置

```powershell
$AlarmConfigName = "TEST-ALARM-CONFIG-$RunId"
$AlarmConfigBody = @{
  alarmConfigureName = $AlarmConfigName
  alarmType = '10'
  deviceAlarmControl = '1'
  alarmConfigurePeriod = '0'
  sceneType = '1'
  deviceIds = @($DeviceId)
  pushEnabled = '1'
  pushMessageType = '10'
  workorderConfigId = 0
} | ConvertTo-Json -Depth 8
Invoke-RestMethod -Method Post -Uri "$AlarmBaseUrl/configure/add" `
  -Headers $Headers -ContentType 'application/json' -Body $AlarmConfigBody
```

回查：

```powershell
$AlarmConfigRows = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/configure/list?pageNum=1&pageSize=20&alarmConfigureName=$AlarmConfigName" `
  -Headers $Headers).rows
$AlarmConfig = $AlarmConfigRows | Where-Object { $_.alarmConfigureName -eq $AlarmConfigName } | Select-Object -First 1
$AlarmConfigureId = [long]$AlarmConfig.alarmConfigureId
$AlarmConfigDetail = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/configure/$AlarmConfigureId" -Headers $Headers).data
$AlarmConfigDetail | Select-Object alarmConfigureId,tenantId,sceneType,alarmType,deviceIds,deviceSet,pushEnabled,pushMessageType
```

预期：`deviceIds` 包含 `$DeviceId`，`deviceSet` 包含 `$DeviceSn`，Push 字段为 `1/10`。

### A-04 启用 Push 配置

```powershell
$PushConfigDetail = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/pushConfig/$PushConfigId" -Headers $Headers).data
$PushConfigDetail.enabled = $true
Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/pushConfig/update" `
  -Headers $Headers -ContentType 'application/json' `
  -Body ($PushConfigDetail | ConvertTo-Json -Depth 12)
$PushConfigAfter = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/pushConfig/$PushConfigId" -Headers $Headers).data
if (-not $PushConfigAfter.enabled) { throw 'Push 配置未启用' }
```

预期：详情启用，Push 日志显示该 configId 的动态队列/HTTP Consumer 已建立。

### A-05 HTTP 上报并回查 Alarm

```powershell
$AlarmCid = "TEST-$RunId-HTTP"
$AlarmBody = @{
  alarmId = $AlarmCid
  deviceSn = $DeviceSn
  gatewaySn = $GatewaySn
  alarmType = '10'
  alarmDegree = '1'
  sceneType = '1'
  cameraType = '1'
  time = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
} | ConvertTo-Json -Depth 8
Invoke-WebRequest -Method Post -Uri "$AlarmBaseUrl/alarm/alarmAdd" `
  -Headers $Headers -ContentType 'application/json' -Body $AlarmBody
```

即使 HTTP 为 2xx，也必须回查：

```powershell
$AlarmRow = $null
for ($attempt = 0; $attempt -lt 20 -and -not $AlarmRow; $attempt++) {
  $result = Invoke-RestMethod -Method Get `
    -Uri "$AlarmBaseUrl/alarm/list?deviceSn=$([uri]::EscapeDataString($DeviceSn))&pageNum=1&pageSize=200" `
    -Headers $Headers
  $AlarmRow = $result.rows | Where-Object { $_.alarmCid -eq $AlarmCid } | Select-Object -First 1
  if (-not $AlarmRow) { Start-Sleep -Milliseconds 500 }
}
if (-not $AlarmRow) { throw 'alarmAdd 返回后仍未查到报警记录' }
$AlarmId = [long]$AlarmRow.alarmId
$AlarmDetail = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/alarm/query/$AlarmId" -Headers $Headers).data
$AlarmDetail
```

### A-06 验证最终 HTTP 接收

```powershell
$ReceiverEvents = Invoke-RestMethod -Method Get -Uri "$ReceiverBaseUrl/_events"
$MatchedEvent = $ReceiverEvents.events | Where-Object {
  ($_.rawBody -like "*$AlarmCid*") -and ($_.rawBody -like "*$DeviceSn*")
} | Select-Object -First 1
if (-not $MatchedEvent) { throw 'HTTP 接收器未收到对应 CID/设备消息' }
$MatchedEvent
```

PASS 条件：

- Alarm 详情存在。
- 接收事件包含 `$AlarmCid`、`$DeviceSn`、当前租户和 `messageType=10`。
- Push 日志或只读 `push_message_log` 指向 `$PushConfigId` 和 `127.0.0.1:19010/alarm-test`。

### A-07 真实 `alarm_queue` 上报

```powershell
$RabbitUser = Read-Host '输入 RabbitMQ 管理端用户名'
$RabbitPassword = Read-Host '输入 RabbitMQ 管理端密码' -AsSecureString
$RabbitPlain = [System.Net.NetworkCredential]::new('', $RabbitPassword).Password
$RabbitBasic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("$RabbitUser`:$RabbitPlain"))
$RabbitHeaders = @{ Authorization = "Basic $RabbitBasic" }
$MqAlarmCid = "TEST-$RunId-MQ"
$MqEnvelope = @{
  cmd = 'dataSync'
  cmdData = @{
    confItems = 1000
    deviceSn = $GatewaySn
    operCode = 259
    rawData = @{
      alarmDegree = '1'; alarmId = $MqAlarmCid; alarmType = '10'; cameraType = '1'
      deviceSn = $DeviceSn; gatewaySn = $GatewaySn; sceneType = '1'
      time = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
    }
    version = 1
  }
  cmdSeq = 1
  servId = 'alarm-api-test'
  times = 1
}
$PublishBody = @{
  properties = @{}
  routing_key = 'alarm_queue'
  payload = ($MqEnvelope | ConvertTo-Json -Depth 12 -Compress)
  payload_encoding = 'string'
} | ConvertTo-Json -Depth 15
$PublishResult = Invoke-RestMethod -Method Post `
  -Uri "$RabbitManagementUrl/api/exchanges/%2F/amq.default/publish" `
  -Headers $RabbitHeaders -ContentType 'application/json' -Body $PublishBody
if (-not $PublishResult.routed) { throw 'alarm_queue 未路由' }
```

再按 A-05、A-06 方法用 `$MqAlarmCid` 回查 Alarm 和接收事件。完成后清除密码变量：

```powershell
$RabbitPlain = $null
$RabbitHeaders = $null
$SecondAssigneeHeaders = $null
$ClosePermissionHeaders = $null
```

### A-08 禁用 Push 验证“入库但不投递”

先记录接收器计数并禁用配置：

```powershell
$BeforeDisableCount = (Invoke-RestMethod -Method Get -Uri "$ReceiverBaseUrl/_events").count
$PushConfigDetail = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/pushConfig/$PushConfigId" -Headers $Headers).data
$PushConfigDetail.enabled = $false
Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/pushConfig/update" `
  -Headers $Headers -ContentType 'application/json' `
  -Body ($PushConfigDetail | ConvertTo-Json -Depth 12)
```

再按 A-05 上报新 CID `TEST-$RunId-DISABLED` 并确认 Alarm 入库，随后：

```powershell
$AfterDisableCount = (Invoke-RestMethod -Method Get -Uri "$ReceiverBaseUrl/_events").count
if ($AfterDisableCount -ne $BeforeDisableCount) { throw 'Push 禁用后仍收到新事件' }
```

若仍收到，检查是否存在另一条同租户、同设备、同 messageType 的启用配置。

## 8. 基准流程 B：企业微信接收人闭环

### 8.1 前置门禁

准备三位仅用于测试的账号：

| 角色 | 平台 userId | 企业微信 UserID | 预期 |
|---|---:|---|---|
| 组成员/观察者 | `$GroupUserId` | `$GroupWecomId` | 收普通报警，不收负责人事件 |
| 第一负责人 | `$FirstAssigneeId` | `$FirstWecomId` | 收普通报警和创建工单 |
| 第二负责人 | `$SecondAssigneeId` | `$SecondWecomId` | 收普通报警和转派事件 |

服务端路由测试需要 CorpID、AgentID、Secret、应用可见范围和有效绑定。若本轮还要复测客户端到达，则必须能人工确认“谁收到/谁未收到”；缺人工条件时只标记“客户端未复测”，不影响服务端闭环判定，也不能把 RabbitMQ 成功写成客户端实收。

先把三人的真实测试值保存为变量：

```powershell
$GroupUserId = [long](Read-Host '输入观察者平台 userId')
$GroupWecomId = Read-Host '输入观察者企业微信 UserID'
$FirstAssigneeId = [long](Read-Host '输入第一负责人平台 userId')
$FirstWecomId = Read-Host '输入第一负责人企业微信 UserID'
$SecondAssigneeId = [long](Read-Host '输入第二负责人平台 userId')
$SecondWecomId = Read-Host '输入第二负责人企业微信 UserID'
```

### 8.2 配置企业微信应用

```powershell
$CorpId = Read-Host '输入 CorpID'
$AgentId = [long](Read-Host '输入 AgentID')
$CorpSecretSecure = Read-Host '输入 CorpSecret' -AsSecureString
$CorpSecret = [System.Net.NetworkCredential]::new('', $CorpSecretSecure).Password
$AppBody = @{corpId=$CorpId;corpSecret=$CorpSecret;agentId=$AgentId;enabled=$true} | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/wecom/app" `
  -Headers $Headers -ContentType 'application/json' -Body $AppBody
$CorpSecret = $null
$AppBody = $null
Invoke-RestMethod -Method Get -Uri "$PushBaseUrl/wecom/app" -Headers $Headers
```

### 8.3 用户绑定和接收组

为三人赋值后执行：

```powershell
$BindingBody = @{
  bindings = @(
    @{userId=$GroupUserId;wecomUserId=$GroupWecomId;enabled=$true},
    @{userId=$FirstAssigneeId;wecomUserId=$FirstWecomId;enabled=$true},
    @{userId=$SecondAssigneeId;wecomUserId=$SecondWecomId;enabled=$true}
  )
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/wecom/userBinding/batch" `
  -Headers $Headers -ContentType 'application/json' -Body $BindingBody
Invoke-RestMethod -Method Get -Uri "$PushBaseUrl/wecom/userBinding/list" -Headers $Headers

$GroupName = "TEST-WECOM-GROUP-$RunId"
$GroupBody = @{
  groupName=$GroupName;enabled=$true
  userIds=@($GroupUserId,$FirstAssigneeId,$SecondAssigneeId)
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri "$PushBaseUrl/recipientGroup" `
  -Headers $Headers -ContentType 'application/json' -Body $GroupBody
$Groups = (Invoke-RestMethod -Method Get -Uri "$PushBaseUrl/recipientGroup/list" -Headers $Headers).data
$Group = $Groups | Where-Object {$_.groupName -eq $GroupName} | Select-Object -First 1
$GroupId = [long]$Group.groupId
if ($GroupId -le 0) { throw '未回查到 GroupId' }
Invoke-RestMethod -Method Get -Uri "$PushBaseUrl/recipientGroup/$GroupId" -Headers $Headers
```

### 8.4 普通报警：接收组三人应收

先检查同租户、同 messageType 的企业微信启用配置；共享环境存在冲突时保存每条详情完整 JSON，再通过 PUT 暂时禁用，测试结束恢复：

```powershell
$ExistingPushRows = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/pushConfig/list?pageNum=1&pageSize=500" -Headers $Headers).rows
$WecomConflicts = @($ExistingPushRows | Where-Object {
  [string]$_.pushChannelType -eq '20' -and [string]$_.messageType -eq '10' -and $_.enabled -eq $true
})
$PausedPushConfigs = @()
foreach ($row in $WecomConflicts) {
  $snapshot = (Invoke-RestMethod -Method Get `
    -Uri "$PushBaseUrl/pushConfig/$($row.activePushConfigId)" -Headers $Headers).data
  $PausedPushConfigs += $snapshot
  $disabled = $snapshot.PSObject.Copy()
  $disabled.enabled = $false
  Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/pushConfig/update" `
    -Headers $Headers -ContentType 'application/json' `
    -Body ($disabled | ConvertTo-Json -Depth 12)
}
```

创建普通报警企业微信配置：

```powershell
$WecomAlarmPushName = "TEST-WECOM-ALARM-PUSH-$RunId"
$WecomAlarmPushBody = @{
  messageType='10';pushChannelType='20';enabled=$true;routeScope='TENANT'
  recipientGroupId=$GroupId;configName=$WecomAlarmPushName;deviceSns=@()
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$PushBaseUrl/pushConfig/add" `
  -Headers $Headers -ContentType 'application/json' -Body $WecomAlarmPushBody
$WecomAlarmPushRows = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/pushConfig/list?pageNum=1&pageSize=20&configName=$WecomAlarmPushName" `
  -Headers $Headers).rows
$WecomAlarmPushConfigId = [long](($WecomAlarmPushRows | Where-Object {
  $_.configName -eq $WecomAlarmPushName
} | Select-Object -First 1).activePushConfigId)
if ($WecomAlarmPushConfigId -le 0) { throw '未回查到普通报警企业微信配置 ID' }
```

重新启用第 7 章的 Alarm 配置后，上报 CID `TEST-$RunId-WECOM-ALARM`，并按 A-05 回查 `$AlarmId`。

服务端 PASS：消息没有 `assigneeId`，按组解析出三名有效目标，并存在三人的发送尝试/日志。客户端到达 PASS：三位组成员均实收、组外人员不收，并额外记录 CID、内部 Alarm ID、Push messageId、configId、groupId 和三人截图/确认。

### 8.5 工单前置：确认报警

工单要求当前 Alarm 配置具备正数 `workorderConfigId` 和 `workorderPushMessageType=25`。当前仓库没有工单模板 CRUD 或被引用模板存在性校验，`workorderConfigId` 只是“启用工单并保存来源值”的兼容关联值；使用测试专用正数即可，但测试结果只能证明关联值生效，不能写成“模板内容已应用”。

取得模板 ID 后，用完整 Alarm 配置详情更新：

```powershell
$WorkorderConfigId = [long](Read-Host '输入测试专用正数 workorderConfigId')
if ($WorkorderConfigId -le 0) { throw 'workorderConfigId 必须为正整数' }
$AlarmConfigForWorkorder = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/configure/$AlarmConfigureId" -Headers $Headers).data
$AlarmConfigBeforeWorkorder = $AlarmConfigForWorkorder.PSObject.Copy()
$AlarmConfigForWorkorder.pushEnabled = '1'
$AlarmConfigForWorkorder.pushMessageType = '10'
$AlarmConfigForWorkorder.workorderPushMessageType = '25'
$AlarmConfigForWorkorder.workorderConfigId = $WorkorderConfigId
Invoke-RestMethod -Method Put -Uri "$AlarmBaseUrl/configure/update" `
  -Headers $Headers -ContentType 'application/json' `
  -Body ($AlarmConfigForWorkorder | ConvertTo-Json -Depth 12)
```

确认报警：

```powershell
$HandleBody = @{
  alarmIds=@($AlarmId);handleStatus='2';identify='0'
  opinion="企业微信工单测试 $RunId";confirmUserId=[long]$CurrentUserId
  handlerName=$CurrentUsername
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$AlarmBaseUrl/handle/update" `
  -Headers $Headers -ContentType 'application/json' -Body $HandleBody
$HandleRows = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/handle/list?alarmId=$AlarmId&pageNum=1&pageSize=10" `
  -Headers $Headers).rows
if (-not ($HandleRows | Where-Object {$_.handleStatus -eq '2'})) { throw '报警未确认到状态 2' }
```

### 8.6 工单创建：只发第一负责人

先按 8.4 的方式检查并暂停同租户 `messageType=25` 的其他启用企业微信配置，再创建本轮配置：

```powershell
$WorkorderPushName = "TEST-WECOM-WORKORDER-PUSH-$RunId"
$WorkorderPushBody = @{
  messageType='25';pushChannelType='20';enabled=$true;routeScope='TENANT'
  recipientGroupId=$GroupId;configName=$WorkorderPushName;deviceSns=@()
} | ConvertTo-Json -Depth 6
Invoke-RestMethod -Method Post -Uri "$PushBaseUrl/pushConfig/add" `
  -Headers $Headers -ContentType 'application/json' -Body $WorkorderPushBody
$WorkorderPushRows = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/pushConfig/list?pageNum=1&pageSize=20&configName=$WorkorderPushName" `
  -Headers $Headers).rows
$WorkorderPushConfigId = [long](($WorkorderPushRows | Where-Object {
  $_.configName -eq $WorkorderPushName
} | Select-Object -First 1).activePushConfigId)
if ($WorkorderPushConfigId -le 0) { throw '未回查到工单企业微信配置 ID' }
```

创建前先验证候选人解析。候选接口使用工单 `messageType=25` 和报警设备 SN，强制当前租户；正常流程只选择 `wecomReachable=true` 的人：

```powershell
$Candidates = (Invoke-RestMethod -Method Get `
  -Uri "$PushBaseUrl/recipientGroup/workorderCandidates?messageType=25&deviceSn=$DeviceSn" `
  -Headers $Headers).data
$FirstCandidate = $Candidates | Where-Object {
  $_.userId -eq $FirstAssigneeId -and $_.wecomReachable -eq $true
}
if (-not $FirstCandidate) { throw '第一负责人不在可达候选人中' }
```

正数负责人会覆盖配置组；`0/null/缺失`则保留配置组模式。

```powershell
$WorkorderBody = @{
  alarmId=$AlarmId;assigneeId=$FirstAssigneeId;assigneeName=$FirstWecomId
  title="测试报警工单-$RunId";content="创建后只通知第一负责人"
} | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri "$AlarmBaseUrl/workorder" `
  -Headers $Headers -ContentType 'application/json' -Body $WorkorderBody
$Workorders = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/workorder/list?alarmId=$AlarmId&pageNum=1&pageSize=20" -Headers $Headers).rows
$Workorder = $Workorders | Where-Object {$_.alarmId -eq $AlarmId} | Select-Object -First 1
$WorkorderId = [long]$Workorder.workorderId
```

用第一负责人自己的 Token 调用 `/workorder/my` 和 `/workorder/my/$WorkorderId`，必须能看到该工单；用第二负责人或其他租户 Token 调用“我的详情”必须返回 `data=null` 或拒绝。PASS：只有第一负责人收到 `ALARM_WORKORDER_CREATED`；观察者和第二负责人不得收到。

### 8.7 工单转派：只发第二负责人

```powershell
$TransferBody = @{
  workorderId=$WorkorderId;assigneeId=$SecondAssigneeId;assigneeName=$SecondWecomId
} | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$AlarmBaseUrl/workorder/transfer" `
  -Headers $Headers -ContentType 'application/json' -Body $TransferBody
$WorkorderAfter = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/workorder/$WorkorderId" -Headers $Headers).data
if ($WorkorderAfter.assigneeId -ne $SecondAssigneeId) { throw '负责人未更新' }
```

PASS：只有第二负责人收到 `ALARM_WORKORDER_TRANSFERRED`；旧负责人和观察者不得收到。

完成工单必须换成第二负责人的 Token/用户头；说明和图片都必填，不能提交 `alarmId`、`status` 或负责人字段：

```powershell
$CompleteBody = @{
  workorderId=$WorkorderId
  handleResult="测试完成-$RunId"
  handlePicture="/test-evidence/$RunId/workorder-complete.jpg"
} | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$AlarmBaseUrl/workorder/complete" `
  -Headers $SecondAssigneeHeaders -ContentType 'application/json' -Body $CompleteBody
$Completed = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/workorder/$WorkorderId" -Headers $Headers).data
if ($Completed.status -ne '2' -or $Completed.handleResult -ne "测试完成-$RunId") {
  throw '工单完成状态或说明错误'
}
if ($Completed.handlePicture -ne "/test-evidence/$RunId/workorder-complete.jpg") {
  throw '处理图片未从 alarm_handle 回填'
}
```

用旧负责人重复完成、用新负责人重复完成、缺说明或缺图片都必须失败，且数据库中不能产生第二次业务写入。

### 8.8 未分配、转派后完成与异常关闭

分别再准备两条不同的已确认报警，得到 `$UnassignedAlarmId` 和 `$CloseAlarmId`。同一报警只能创建一张工单，不能复用前面的 `$AlarmId`。

未分配模式：

```powershell
$UnassignedBody = @{
  alarmId=$UnassignedAlarmId;assigneeId=0
  title="未分配工单-$RunId";content='先按组通知，随后转派'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$AlarmBaseUrl/workorder" `
  -Headers $Headers -ContentType 'application/json' -Body $UnassignedBody
$UnassignedRows = (Invoke-RestMethod -Method Get `
  -Uri "$AlarmBaseUrl/workorder/list?alarmId=$UnassignedAlarmId&pageNum=1&pageSize=20" `
  -Headers $Headers).rows
$UnassignedWorkorderId = [long]($UnassignedRows | Select-Object -First 1).workorderId
```

断言 `assigneeId=0`、状态 `0`，配置组收到 CREATED 路由；任意用户的 `/workorder/my` 都不包含它，直接 `/complete` 失败。随后调用 `/transfer` 指定正数新负责人，只产生该新负责人的 TRANSFERRED 通知；再由新负责人提交必填说明和图片完成。

异常关闭使用另一张工单：

```powershell
$CloseBody = @{
  workorderId=$CloseWorkorderId
  handleResult="测试异常关闭-$RunId"
  handlePicture="/test-evidence/$RunId/workorder-close.jpg"
} | ConvertTo-Json
Invoke-RestMethod -Method Put -Uri "$AlarmBaseUrl/workorder/close" `
  -Headers $ClosePermissionHeaders -ContentType 'application/json' -Body $CloseBody
```

调用账号只需具备现有 `alarm:workorder:close` 权限；代码不额外判断管理员身份。断言状态为 `3`，原因、图片和实际关闭人写入处理记录；再次关闭、转派、完成或删除都失败。本轮不要求完成/关闭后二次通知。

## 9. 报警配置 CRUD 测试

| 用例 | 操作 | 必须验证 |
|---|---|---|
| AC-C | POST `/configure/add` | 唯一名称可回查、租户正确、设备关系存在 |
| AC-R1 | GET `/configure/list` | 分页、名称筛选、另一租户不可见 |
| AC-R2 | GET `/configure/{id}` | deviceIds/deviceSet/Push 字段完整 |
| AC-U1 | PUT `/configure/update` 改名称 | 详情回读新名称 |
| AC-U2 | `deviceIds` 不传/null | 原关系保留 |
| AC-U3 | `deviceIds=[]` | 关系清空；验证后恢复 |
| AC-U4 | 非空 `deviceIds` | 完整替换且只能当前租户设备 |
| AC-D | DELETE `/configure/delete/{ids}` | 详情 `data=null`、关系清理 |

异常：配置 ID 为空、设备 ID 非正数/跨租户、`workorderConfigId<0` 应失败。

## 10. 报警记录 CRUD 与查询测试

| 用例 | 接口 | PASS 条件 |
|---|---|---|
| AR-C1 | POST `/alarm/alarmAdd` | 按 CID 查到一条内部记录 |
| AR-C2 | `alarm_queue` 259 | routed 且按 CID 查到记录 |
| AR-R1 | GET `/alarm/list` | 分页、设备/状态筛选正确；响应中可读 CID，但当前请求参数 `alarmCid` 不参与过滤 |
| AR-R2 | GET `/alarm/query/{id}` | 详情 ID/CID/设备/时间正确 |
| AR-R3 | 图片接口 | 有图片数据时返回路径/内容；无数据标未适用 |
| AR-R4 | 统计接口 | 时间、设备、租户条件正确 |
| AR-U | PUT `/alarm` | 修改本轮状态/意见后详情回读 |
| AR-D1 | DELETE `/alarm/{id}` | 列表/详情不可见 |
| AR-D2 | DELETE `/alarm/{id1},{id2}` | 只删除本轮两条记录 |

禁止用 SQL INSERT 代替 Create。`/alarm/export` 当前不作为通过用例。

## 11. 报警停止、重复和异常测试

| 用例 | 输入 | 断言 |
|---|---|---|
| STOP-CID | `alarm_queue` operCode=260，复用 CID | `alarmEndtime` 非空、状态停止 |
| STOP-DEVICE | `/alarm/alarmStopByDeviceSn` + deviceSn/time | 该设备符合条件的活跃报警关闭 |
| STOP-IRMS | `/alarm/alarmStopByIrmsSn` + irmsSn/time | 该网关活跃报警关闭 |
| STOP-REPEAT | 相同 CID 重复停止 | 无第二条活跃记录，按幂等处理 |
| STOP-MISSING | 不存在 CID | 无活跃路由，记录告警日志，不误关其他数据 |
| PUSH-NO-CONFIG | 删除/不匹配 Alarm 配置后上报 | Alarm 可入库，默认不 Push |
| PUSH-OFF | pushEnabled=0 | Alarm 有记录，最终接收无新增 |
| TYPE-MISMATCH | Alarm 和 Push messageType 不一致 | 无目标 Push 配置 |

停止早到/后到的可靠事件链路需要真实 RabbitMQ、worker 和分片环境；条件不足标 BLOCKED，不伪造 PASS。

## 12. 报警处理与工单测试

| 用例 | 操作 | 预期 |
|---|---|---|
| HANDLE-SAVE | `/handle/save` | 处理记录通常为已处理 `1` |
| HANDLE-CONFIRM | `/handle/update` | 回读 `handleStatus=2` |
| WO-NOT-CONFIRMED | 未确认直接 POST `/workorder` | 失败“报警未确认” |
| WO-NO-TEMPLATE | 配置模板 ID 非正数 | 失败“未关联工单模板” |
| WO-CREATE | 已确认、有模板 | 工单 `status=0`、负责人正确 |
| WO-CREATE-UNASSIGNED | `assigneeId=0/null/缺失` | 保存为 `0`、不进入任何人的“我的工单”、按组路由 |
| WO-DUP | 同一报警重复创建 | 失败，不产生第二张工单 |
| WO-MY | `/workorder/my`、`/workorder/my/{id}` | 仅当前租户、当前正数负责人可见 |
| WO-CANDIDATE | `/recipientGroup/workorderCandidates` | DEVICE/TENANT 路由正确，按 userId 去重并返回 wecomReachable |
| WO-TRANSFER | `/workorder/transfer` | 负责人变更、只发新负责人 |
| WO-TRANSFER-INVALID | 新负责人 `0/null/负数` | 失败且负责人不变 |
| WO-COMPLETE | 当前负责人传说明和图片 | `status=2`、结果和图片回读，处理人正确 |
| WO-COMPLETE-DENY | 未分配/非负责人/缺字段/重复完成 | 失败且无第二次业务写入 |
| WO-CLOSE | 有 close 权限、原因必填、图片可选 | `status=3`，关闭原因和实际操作人回读 |
| WO-TERMINAL | 对状态 `2/3` 再编辑/转派/完成/关闭/删除 | 全部失败 |
| WO-DELETE | DELETE 非终态 `/workorder/{ids}` | 本轮工单不可见；含终态或跨租户 ID 时整批回滚 |

## 13. Push 配置生命周期和其他通道

| 用例 | 操作 | 断言 |
|---|---|---|
| PC-C | 先禁用新增 | 列表/详情可回查，不投递 |
| PC-ENABLE | 完整对象启用 | 路由、动态队列和 Consumer 建立 |
| PC-UPDATE | 改地址或接收组 | 详情与后续投递使用新值 |
| PC-DISABLE | enabled=false | 新报警不再由该配置投递 |
| PC-DELETE | DELETE | 详情不可见，运行路由/队列清理 |
| PC-RESTART | 重启 Push | 启用配置从 DB 恢复运行态 |

通道判定：

- HTTP：本手册 A 流程可自动验证。
- MQTT：必须检查真实订阅端，缺 Broker 标未执行。
- 普通 WS：先取 sessionId，再连接并发首帧。
- pushKey WS：绑定配置、首帧 pushKey、消息后发回执。
- 企业微信：API 业务成功 + 目标账号实收 + 禁收账号未收。
- 短信/邮件：当前不支持。

## 14. 租户隔离与权限测试

准备租户 B 的合法账号和 Token：

1. 租户 B 用相同名称查询 Alarm/Push 配置，结果应为空。
2. 租户 B 查询租户 A 的 ID，`data=null` 或拒绝。
3. 跨租户更新/删除整个请求应失败，不能部分成功。
4. 请求体写租户 A/B 的任意 `tenantId`，服务仍使用当前登录租户。
5. 缺权限接口返回 403，记录权限编码并交管理员，不改数据库绕过。
6. 租户 B 不能通过全部详情、我的详情、编辑、转派、完成、关闭或删除访问租户 A 工单。

## 15. 按现象排障

| 现象 | 第一检查点 | 可能原因 | 下一步/责任人 |
|---|---|---|---|
| 配置新增成功但列表查不到 | 唯一名称、当前租户、分页 | tenant Header、逻辑删除、字段不完整 | 测试核对请求；开发查 Mapper |
| alarmAdd 2xx 但查不到记录 | CID、Alarm error 日志、时间分片 | Controller 吞异常、设备缓存缺失、字段/分片错误 | 开发查 `报警推送异常` 和 insert stage |
| 有 Alarm 但无 push.alarm | `push.open`、Alarm 配置详情 | 未匹配、pushEnabled=0、require-matched-config | 测试核字段；运维查 Nacos |
| Push 有消息但没命中配置 | Push 列表、tenantId/deviceSn/messageType | DEVICE/TENANT 错、messageType 错、配置禁用 | 测试修配置 |
| 命中配置但 HTTP 未收到 | configId Consumer、pushAddress、push_message_log | 地址错误、Consumer 未建立、接收器未启动 | 测试查接收器；开发查 Consumer |
| 企业微信无人收到 | App、组详情、绑定列表 | 组空、用户未绑定、应用禁用/不可见、凭据错误 | 测试查配置；企业微信管理员查应用 |
| 工单发错人 | payload 顶层 assigneeId | 负责人 ID/绑定错误，或 assigneeId 被传成字符串 | 开发查 payload；测试核负责人 |
| 禁用/删除后仍收到 | 同 messageType 所有配置 | 另一启用配置、运行态未清理 | 测试排除冲突；开发查动态队列 |
| 删除接收组失败 | Push 配置列表 | 组仍被配置引用 | 先禁用/删除引用配置 |

推荐日志关键字：`alarmId`、`alarmCid`、`push.alarm`、`activePushConfigId/configId`、`messageType`、`recipientGroupId`、`assigneeId`、`WeCom push delivery failed`。

SQL 仅用于只读核验；优先使用仓库 `hpis-alarm/src/test/resources/sql/alarm-push-api-check.sql`，执行前确认库和 runId，禁止修改其中语句为写操作。

## 16. 测试结果记录模板

### 16.1 2026-07-23 本次自动化执行记录

| 检查项 | 实际结果 | 判定 |
|---|---|---|
| Maven 运行时 | JDK `1.8.0_321`，`-Dfile.encoding=UTF-8` | PASS |
| 工单与候选人聚焦回归 | Alarm 27 个、Push 20 个，共 47 个；0 失败、0 错误 | PASS |
| Alarm 全量测试 | 168 个；0 失败、0 错误、3 个依赖外部运行参数的 runner 跳过 | PASS |
| Push 全量测试 | 71 个；0 失败、0 错误、0 跳过 | PASS |
| Postman 静态校验 | 78 个请求、42 个环境变量；变量引用、脚本语法和 raw JSON 请求体校验通过 | PASS |
| 本地真实服务启动 | Nacos、Push、Alarm 和两个 HTTP 接收器启动成功；Push 先于 Alarm 启动 | PASS |
| Postman 接口回归 | 78 个请求、48 个测试脚本、48 个断言；请求和断言均 0 失败 | PASS |
| 配置 CRUD 与清理 | Alarm 配置、企业微信应用/绑定/接收组和四条 Push 配置均通过 API 创建、查询、修改和删除/禁用；最终启用配置、组、绑定、启用应用计数均为 0 | PASS |
| 工单与租户闭环 | 跨租户详情不可见、转派失败；工单 `34/36/37` 最终状态为 `2/2/3`，负责人分别为第二负责人、第二负责人、第一负责人 | PASS |
| `alarm_handle`只读核验 | 三张工单均写入说明、图片和实际处理人；异常关闭记录处理人为关闭操作用户 | PASS |
| RabbitMQ 真实入口 | `run-alarm-push-e2e.ps1`通过 RabbitMQ 管理接口向 `alarm_queue`发布并到达 HTTP 接收器；全部检查项为 `true` | PASS |
| Push 投递日志 | HTTP 通道成功 8 次；企业微信通道使用本地假地址产生 8 次失败发送记录，证明配置路由、动态 Consumer、接收人解析和发送尝试已执行 | PASS（服务端范围） |
| 企业微信客户端到达 | 本轮按约定不使用真实 CorpSecret，不重复取得客户端实收证据 | 未复测，不声明客户端 PASS |
| 数据库迁移 | 未执行 DDL；当前库缺少 `idx_alarm_workorder_tenant_assignee_status`（只读检查为 0） | 发布前置条件，不能据此上线 |

本次已完成本地真实 Alarm/Push/RabbitMQ/MySQL/Redis 服务端闭环。Postman 使用一次性租户上下文、测试设备缓存和本地假企业微信地址，不包含生产网关鉴权或企业微信客户端实收；报告为 `target/alarm-push-postman-e2e/newman-1784753745.json`。正式发布前必须按第 3 号文档执行并核验工单联合索引迁移，再使用部署环境合法 Token 复跑；只有需要声明“企业微信客户端到达 PASS”时，才补充目标账号实收证据。

| 字段 | 实际值 |
|---|---|
| 用例编号 |  |
| 执行时间/代码版本 |  |
| PASS / FAIL / BLOCKED |  |
| 首个失败层 | Alarm 配置 / 入库 / MQ / Push 路由 / Consumer / 接收人 / 最终通道 |
| tenantId / runId |  |
| deviceId / deviceSn |  |
| alarmConfigureId |  |
| alarmCid / alarmId |  |
| pushConfigId / messageId |  |
| groupId / workorderId |  |
| assigneeId / handlerId |  |
| workorder status / handlePicture |  |
| 请求与响应摘要 |  |
| 预期接收人 |  |
| 禁止接收人 |  |
| 实际接收结果 |  |
| 日志/截图/报告路径 |  |

## 17. 清理、恢复和残留检查

只处理名称或 CID 含本轮 `$RunId` 的对象。推荐顺序：

1. 完成/关闭后的工单按审计要求保留；仅通过 DELETE 清理仍处于非终态的本轮工单，终态工单不能删除。
2. 删除本轮处理记录和报警记录。
3. 将本轮 Push 配置设为 `enabled=false`，确认 Consumer 停止。
4. 删除本轮 Push 配置，详情回查不可见。
5. 恢复测试前被临时禁用的完整 Push 配置快照，并逐条回读。
6. 删除没有被引用的本轮接收组。
7. 企业微信 App 和用户绑定按环境策略保留；不要误删共享绑定。
8. 删除本轮 Alarm 配置，详情回查不可见。
9. 清空 HTTP 接收器事件并清除内存凭据。

示例：

```powershell
if ($WorkorderId -and $WorkorderStatus -notin @('2','3')) {
  Invoke-RestMethod -Method Delete -Uri "$AlarmBaseUrl/workorder/$WorkorderId" -Headers $Headers
}
if ($AlarmId) {
  Invoke-RestMethod -Method Delete -Uri "$AlarmBaseUrl/alarm/$AlarmId" -Headers $Headers
}
if ($PushConfigId) {
  $cfg = (Invoke-RestMethod -Method Get -Uri "$PushBaseUrl/pushConfig/$PushConfigId" -Headers $Headers).data
  if ($cfg) {
    $cfg.enabled = $false
    Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/pushConfig/update" `
      -Headers $Headers -ContentType 'application/json' -Body ($cfg | ConvertTo-Json -Depth 12)
    Invoke-RestMethod -Method Delete -Uri "$PushBaseUrl/pushConfig/$PushConfigId" -Headers $Headers
  }
}
foreach ($testConfigId in @($WecomAlarmPushConfigId,$WorkorderPushConfigId)) {
  if ($testConfigId) {
    $testCfg = (Invoke-RestMethod -Method Get `
      -Uri "$PushBaseUrl/pushConfig/$testConfigId" -Headers $Headers).data
    if ($testCfg) {
      $testCfg.enabled = $false
      Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/pushConfig/update" `
        -Headers $Headers -ContentType 'application/json' `
        -Body ($testCfg | ConvertTo-Json -Depth 12)
      Invoke-RestMethod -Method Delete `
        -Uri "$PushBaseUrl/pushConfig/$testConfigId" -Headers $Headers
    }
  }
}
foreach ($snapshot in $PausedPushConfigs) {
  Invoke-RestMethod -Method Put -Uri "$PushBaseUrl/pushConfig/update" `
    -Headers $Headers -ContentType 'application/json' `
    -Body ($snapshot | ConvertTo-Json -Depth 12)
}
if ($GroupId) {
  Invoke-RestMethod -Method Delete -Uri "$PushBaseUrl/recipientGroup/$GroupId" -Headers $Headers
}
if ($AlarmConfigureId) {
  Invoke-RestMethod -Method Delete -Uri "$AlarmBaseUrl/configure/delete/$AlarmConfigureId" -Headers $Headers
}
Invoke-RestMethod -Method Delete -Uri "$ReceiverBaseUrl/_events"
$Token = $null
$Headers = $null
$RabbitHeaders = $null
```

删除组失败通常表示仍有 Push 配置引用，先查 `/pushConfig/list`，不要强制删表。共享环境要求保留审计数据时不删除报警/工单，在测试报告标记“按审计要求保留”，但仍必须恢复被修改的共享配置。
