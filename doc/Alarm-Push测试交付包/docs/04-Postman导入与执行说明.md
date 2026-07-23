# Postman 导入与执行说明

## 1. 使用范围

本说明对应当前交付包 Collection，覆盖：

- Alarm 配置新增、查询、修改、删除；
- 企业微信租户应用、业务用户绑定、接收组的受支持生命周期；
- HTTP 与企业微信 `ActivePushConfig` 的新增、查询、修改、删除；
- 普通报警 `pushMessageType=10`；
- 工单 `workorderPushMessageType=25`、候选人、具体负责人、未分配、转派、完成和异常关闭；
- 失败请求、配置恢复和 API 清理。

Collection 只允许在专用测试租户运行。企业微信应用是“每租户一条”的配置且没有 DELETE 接口，绑定也可能覆盖相同业务用户的旧值；共享租户必须改用手工流程并先保存可恢复快照。

## 2. 导入文件

1. 导入 `postman/hpis-alarm-push.postman_collection.json`。
2. 导入 `postman/hpis-alarm-push.postman_environment.json`。
3. 选择 `HPIS Alarm Push - Fresh Environment` 环境。
4. 只在 Postman 本地当前值中填写 Token、企业微信 Secret 和真实账号，不把真实值导出、提交或截图。

仓库中的 `src/test/resources/postman` 是自动化测试副本，必须与交付包版本保持字节一致；测试人员只导入交付包文件。

## 3. 环境变量

### 3.1 服务和凭据

| 变量 | 必填 | 说明 |
|---|---|---|
| `alarmBaseUrl` | 是 | Alarm 网关或直连地址，默认示例 `http://127.0.0.1:18806` |
| `pushBaseUrl` | 是 | Push 网关或直连地址，默认示例 `http://127.0.0.1:8812` |
| `receiver10BaseUrl` | HTTP 测试必填 | 普通报警 HTTP 接收器 |
| `receiver25BaseUrl` | 工单 HTTP 测试必填 | 工单 HTTP 接收器 |
| `token` | 是 | 第一负责人且具备配置、工单接口权限的同租户 Token |
| `secondAssigneeToken` | 是 | 第二负责人同租户 Token，用于验证完成所有权 |
| `closePermissionToken` | 是 | 具备 `alarm:workorder:close` 权限的同租户 Token；代码不额外识别管理员身份 |
| `otherTenantToken` | 跨租户用例必填 | 另一租户用户 Token，只用于验证详情不可见和转派失败 |
| `tenantId` | 是 | 三个 Token 对应的同一测试租户 |
| `otherTenantId` | 跨租户用例必填 | `otherTenantToken`对应的另一租户，必须与 `tenantId`不同 |

Alarm 服务自身的默认端口是 `8806`；Postman Environment 使用 `18806` 作为本地隔离联调端口，避免与 IDEA 中已启动的 `8806` 实例冲突。执行前必须按本次实际启动端口修改 `alarmBaseUrl`：直连默认实例用 `http://127.0.0.1:8806`，由联调脚本启动隔离实例时才保留 `http://127.0.0.1:18806`。

通过网关执行时由网关建立登录与租户上下文。直连 Alarm/Push 微服务时，Collection 根级预请求脚本会根据当前 `token/userId`注入 `Authorization`、`user_id`和 `username`；第二负责人、关闭用户和跨租户请求使用各自变量覆盖这些头。该方式仅用于受控本地联调，不能代替生产网关鉴权测试。

### 3.2 用户和企业微信

| 变量 | 必填 | 说明 |
|---|---|---|
| `userId` | 是 | `token` 对应业务用户 ID、第一负责人 |
| `secondUserId` | 是 | `secondAssigneeToken` 对应业务用户 ID、转派目标 |
| `closeUserId` | 是 | `closePermissionToken` 对应业务用户 ID，用于核验异常关闭实际处理人 |
| `otherUserId` | 跨租户用例必填 | `otherTenantToken`对应业务用户 ID |
| `wecomCorpId` | 企业微信测试必填 | 专用测试租户 CorpID |
| `wecomCorpSecret` | 企业微信测试必填 | 只填本地当前值，禁止导出真实 Secret |
| `wecomAgentId` | 企业微信测试必填 | 正整数 AgentID |
| `wecomUserId` | 企业微信测试必填 | 第一业务用户的企业微信 UserID |
| `secondWecomUserId` | 企业微信测试必填 | 第二业务用户的企业微信 UserID |

### 3.3 设备和运行变量

| 变量 | 来源/用途 |
|---|---|
| `deviceAId/deviceASn/deviceAGatewaySn` | 当前租户设备 A；具体负责人和异常关闭报警 |
| `deviceBId/deviceBSn/deviceBGatewaySn` | 当前租户设备 B；未分配工单报警 |
| `normalDeviceId/normalDeviceSn` | 不应命中 `messageType=10` 的负向设备 |
| `workorderConfigId` | 测试专用正整数兼容关联值；当前代码不校验模板实体存在 |
| `runId` | 留空，Collection 根脚本自动生成 |

以下变量由 Collection 自动回填，不要预填：

`internalAlarmId`、`unassignedAlarmId`、`closeAlarmId`、`emergencyAlarmConfigureId`、`normalAlarmConfigureId`、`recipientGroupId`、`pushConfig10Id`、`pushConfig25Id`、`wecomPushConfig10Id`、`wecomPushConfig25Id`、`workorderId`、`unassignedWorkorderId`、`closeWorkorderId`。

### 3.4 一键本地回归脚本

先使用 Java 8 打包 Alarm 和 Push，并确认本机 Nacos、MySQL、Redis、RabbitMQ 已启动且 Nacos 配置指向测试库；脚本不会启动 Nacos，也不会执行数据库迁移：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\studyProject\hpis2.0\hpis\hpis-alarm\src\test\resources\scripts\run-alarm-push-postman-e2e.ps1'
```

脚本自动生成一次性租户、四类用户上下文和三台测试设备缓存，启动 Push、Alarm 与两个 HTTP 接收器，使用本地不可达企业微信地址运行 78 个请求，最后清理本轮 Redis 上下文和 API 配置。Newman JSON、导出环境和日志写入各模块 `target`，不得提交；除非使用 `-KeepServices`，脚本会停止自己启动的进程。它验证的是直连微服务的服务端闭环，不验证生产网关鉴权、企业微信客户端实收，也不补做缺失 DDL。

## 4. 启动本地 HTTP 接收器

仓库已有接收器脚本时分别监听 19010 和 19025；也可以替换为测试环境可访问的回调服务。Push 的 HTTP `pushAddress` 示例是 `127.0.0.1:19010/...`，不包含 `http://`，因为当前 Consumer 会补协议。

运行前验证：

```powershell
Invoke-RestMethod http://127.0.0.1:19010/_events
Invoke-RestMethod http://127.0.0.1:19025/_events
```

如果 Push 运行在容器或远程主机，`127.0.0.1` 指向 Push 自身，必须把 Collection 中地址改成 Push 可访问的接收器地址。

## 5. 执行前检查

- [ ] Push 先启动，Alarm 后启动，均连接正确 Nacos、数据库、Redis 和 RabbitMQ。
- [ ] 已按 `03-运行配置与SQL同步说明.md` 完成结构同步，但未由本测试自动执行 DDL。
- [ ] `alarm_queue`、`push.alarm` 及动态配置队列具备声明和消费权限。
- [ ] 三个 Token 属于同一测试租户，且用户 ID 与 Token 身份一致。
- [ ] 第一、第二用户是专用测试用户，企业微信应用对二人可见。
- [ ] `push.wecom.secret-key` 已安全配置；Environment 中 CorpSecret 不是占位符。
- [ ] 设备 ID/SN 属于当前租户，Redis/设备服务能解析。
- [ ] 已执行只读 `sql/alarm-push-api-setup.sql`，缺表、缺列、缺索引均已处理。
- [ ] 不存在上一轮 `api-push-e2e-*` 配置；存在时先通过接口清理。

## 6. Collection 顺序

Collection 必须按目录顺序单线程执行，不要并发运行。报警写入、动态 Consumer 重建或 MQ 投递较慢时，在相关请求之间配置测试环境允许的延迟，并以查询结果为准。

### 6.1 `00 - Receiver`

清空 10、25 两个接收器。只清理本地测试接收器内存事件。

### 6.2 `01 - Alarm Configure`

1. 创建紧急和负向 Alarm 配置。
2. 列表按唯一名称回填两个配置 ID。
3. 修改紧急配置名称。
4. 详情读回 `pushMessageType=10`、`workorderPushMessageType=25` 和更新名称。

所有写入都走 `/configure` 接口。`tenantId`由服务端当前上下文生成；`deviceIds`必须属于当前租户。

### 6.3 `02 - WeCom App Binding and Recipient Group`

严格按以下资源依赖执行：

1. PUT 企业微信应用，GET 验证 `secretConfigured=true`且响应无 Secret。
2. 批量创建两个 `userId → wecomUserId` 绑定。
3. 禁用再恢复第一绑定，验证修改语义。
4. 创建接收组和成员，回填 `recipientGroupId`。
5. PUT 修改组名和成员；重复 userId 应在保存后去重。
6. GET 详情验证结果。

应用、绑定、接收组是独立资源，不能与 ActivePushConfig 合并成一个请求。

### 6.4 `03 - Push Config`

创建四条独立配置：

| 配置 | messageType | channel | routeScope | 关系 |
|---|---:|---:|---|---|
| HTTP 普通报警 | 10 | 10 | DEVICE | deviceA/deviceB SN |
| HTTP 工单 | 25 | 10 | DEVICE | deviceA/deviceB SN |
| 企业微信普通报警 | 10 | 20 | TENANT | recipientGroupId |
| 企业微信工单 | 25 | 20 | TENANT | recipientGroupId |

列表回填四个配置 ID；随后禁用/恢复企业微信 25 配置，证明更新接口生效。候选接口使用 `messageType=25 + deviceSn`，断言两名用户只出现一次且 `wecomReachable=true`。

### 6.5 `04 - Alarm messageType 10`

通过 `/alarm/alarmAdd` 创建具体负责人、未分配、异常关闭三条紧急报警和一条负向报警。最后调用 `/alarm/list`，在响应 `rows` 中按完整 `alarmCid` 精确定位三个内部 Long ID。

当前 `/alarm/list` 虽返回 `alarmCid`，但请求参数 `alarmCid`不参与分页过滤；Collection 不依赖该无效筛选，也不使用 SQL 回填 ID。

### 6.6 `05 - Workorder Ownership Lifecycle`

按顺序验证：

1. 批量把三条报警确认到 `handleStatus=2`。
2. 为第一用户创建具体负责人工单，验证“我的工单”。
3. 使用另一租户上下文查询该工单详情并尝试转派，分别验证不可见和写操作拒绝。
4. 通用 PUT 只修改标题/内容；请求中的状态、租户、负责人不得生效。
5. 缺图片完成失败；同一报警重复创建失败。
6. 转派到第二用户，旧负责人完成失败，第二负责人带说明和图片完成为状态 `2`。
7. 创建 `assigneeId=0` 未分配工单，验证不进入第一用户“我的工单”且不能完成。
8. `assigneeId=0` 转派失败；转派给第二用户后由第二用户完成。
9. 用第三条报警创建工单，使用 close 权限 Token 异常关闭为状态 `3`，重复关闭失败。

完成/关闭不会在本轮自动断言企业微信客户端实收；路由、发送尝试和日志通过只读 SQL/服务日志保存证据。

### 6.7 `06 - Negative Config and Failure`

按请求名称执行 Push 禁用/恢复、HTTP 不可达/恢复、Alarm 工单 messageType 清空能力检查/恢复。每个负例后必须立即执行恢复请求。`workorderPushMessageType=null` 当前动态更新可能被忽略，该请求用于暴露实际契约，不能把 HTTP 200 自动判为字段已清空。

### 6.8 `07 - Cleanup`

删除顺序不能改变：企业微信/HTTP Push 配置 → 接收组 → 用户绑定 → 禁用企业微信应用 → Alarm 配置 → 接收器事件。组被配置引用时删除会失败，所以必须先删四条 Push 配置。

完成/关闭工单属于终态，接口设计不允许删除；按审计要求保留，或取得测试库运行数据清理授权后使用交付包清理脚本。企业微信应用没有 DELETE 接口，专用测试租户只将其禁用。

## 7. 结果判定

| 检查点 | PASS | FAIL |
|---|---|---|
| 配置 CRUD | API 写后读一致、删除后不可见 | 只看 200，或字段/关系未生效 |
| 租户边界 | 请求体不能切租户，跨租户 ID 不可访问 | 可读写其他租户数据 |
| 候选人 | 路由命中、userId 去重、可达性正确 | 禁用/跨租户/不匹配成员进入结果 |
| 工单所有权 | 我的工单、转派、完成操作者符合负责人 | 未分配或非负责人可完成 |
| 工单终态 | 完成 `2`、关闭 `3`，重复写失败 | 重复写或状态回退 |
| 处理证据 | 说明、图片、实际处理人同步到 `alarm_handle` | 只更新工单或图片丢失 |
| Push 闭环 | HTTP 实收；企业微信有路由、发送尝试和日志证据 | 只进入上游 MQ、无配置消费/发送证据 |
| 清理 | 配置资源通过 API 删除/禁用，无误删 | SQL 代替配置 CRUD 或遗留启用配置 |

依赖服务、权限、设备或测试账号不可用时标记 `BLOCKED` 并写明依赖。Postman 未实际运行时只能记录“集合静态校验通过/待环境执行”，不能写 `PASS`。

## 8. 企业微信验证边界

此前链路已验证企业微信客户端到达能力。本轮回归不强制再次取得客户端截图，但必须至少保存：

- Alarm/工单消息中的 tenantId、messageType、deviceSn、assigneeId；
- 命中的 ActivePushConfig、recipientGroupId 和候选人解析结果；
- RabbitMQ 动态队列消费证据；
- `push_message_log` 的目标、状态和失败详情；
- 工单负责人、状态以及 `alarm_handle` 的说明、图片和实际处理人。

若本轮仍要声称“企业微信实际到达 PASS”，则必须额外提供目标账号实收证据；路由和发送日志只能证明服务端闭环。
