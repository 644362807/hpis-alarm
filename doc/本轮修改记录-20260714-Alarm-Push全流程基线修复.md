# 本轮修改记录：Alarm → Push 全流程基线修复

## 1. 修改背景

本轮目标是先完成当前 Alarm/Push 基线测试，不实现后续 `pushBindingId`、企业微信或工单转派。验收要求是 Alarm 和 Push 的配置增删改查全部走接口，并把接口实例、真实链路和结果保存在同一测试文档中。

## 2. Alarm 修改

### 2.1 报警配置与租户

- `/configure` 新增、列表、详情、修改、删除强制使用当前登录租户，忽略请求体租户。
- 所有报警类型统一按 `deviceIds` 从 Redis `device_id2:{id}` 解析设备，并校验设备属于当前租户。
- 详情回填 `deviceIds` 和 `deviceSet`；设备缓存缺失时仍保留 `deviceSet`，不让详情整体失败。
- 更新未传 `deviceIds` 时保留设备关系，空数组清空，非空数组替换。
- 删除配置同步清理设备关系、时间段关系和相关缓存。
- `workorderConfigId=0` 约定为未关联，负数拒绝。
- AlarmConfigure 的虚拟字段增加 `@TableField(exist=false)`，修复列表把虚拟字段当数据库列的问题。

### 2.2 报警匹配与推送

- 补充报警类型 10“紧急报警”，未知类型返回原始值，不再空指针。
- 事务提交后按 `tenantId + sceneType + deviceSn + alarmType` 匹配服务端报警配置。
- 新增 `alarm.push.require-matched-config`，默认 `true`；无匹配配置时不推送。
- 命中配置后优先使用 `pushMessageType`；原有 `push.alarm` payload 和队列名不变。

## 3. Push 修改

### 3.1 配置与租户

- 列表、详情、导出、新增、更新、删除、pushKey 绑定和解绑全部强制当前租户。
- 批量请求中只要存在一个非当前租户 ID，就拒绝整批，不做部分修改。
- `deviceSns`、`enabled` 为空时做安全处理。
- 新增配置显式写 `del_flag=0`；查询和更新兼容历史 `del_flag=NULL`，避免“新增成功但列表不可见”。
- 当前运行环境的 MyBatis-Plus Lambda Wrapper 参数绑定不稳定，租户 CRUD 改为明确 Mapper SQL。

### 3.2 运行态生命周期

- HTTP/MQTT Consumer 按 `activePushConfigId` 注册停止句柄。
- 更新时先停止旧 Consumer，再按新配置重建。
- 禁用时移除路由、停止 Consumer、删除配置队列。
- 删除时清理 Consumer、Redis 路由、队列、pushKey 和设备关系。
- 服务启动时以 DB 启用配置为事实源，重建配置/设备 Redis 路由、队列和 Consumer。
- DB 修改在事务内完成，Redis/Rabbit/Consumer 运行态操作放到事务提交后，运行态失败不反向回滚 Alarm 业务。

## 4. 测试工具与文档

- 新增 `src/test/resources/scripts/run-alarm-push-e2e.ps1`，可重复执行当前完整基线。
- 重写 `src/test/resources/http/alarm-push-api.http`，移除本轮工单和 25 场景。
- 重写 `doc/报警Push接口测试用例-全新环境.md`，集中保存 CRUD 实例、MQ 报文、通道矩阵和实测结果。
- 更新 `报警运行配置说明.md`、`报警分片数据链路说明.md` 和 `报警第一阶段优化变更总览.md`。

## 5. 验证结果

| 验证 | 结果 |
|---|---|
| Alarm 定向单测 | 17 个，0 失败 |
| Push 定向单测 | 9 个，0 失败 |
| Alarm/Push 父工程打包 | PASS |
| Alarm 启动与 `/alarm/list` smoke | PASS |
| Push 冷启动 DB 恢复 | PASS |
| E2E 运行号 `20260714225038` | 全部断言 PASS |

E2E 覆盖：

- Alarm 配置新增、列表、详情、修改、删除；
- Push 配置新增、列表、详情、启用、禁用、删除；
- HTTP `/alarm/alarmAdd`；
- 真实 RabbitMQ `alarm_queue`；
- 两种入口到 HTTP 最终接收；
- Push 禁用后停止投递；
- Alarm/Push 跨租户列表和详情隔离；
- 所有测试配置通过接口清理。

## 6. 兼容与回滚

- 配置 API 路径、`alarm_queue`、`push.alarm`、MQ payload 和 Redis 路由键格式保持不变。
- 临时兼容回滚只需设置 `alarm.push.require-matched-config=false`。
- 不建议回滚租户边界、Push `del_flag=0` 或 Consumer 停止逻辑，否则会重新出现配置泄漏、配置不可见或旧 Consumer 继续投递。

## 7. 未纳入本轮

- MQTT、普通 WebSocket 和 pushKey WebSocket 已记录测试步骤，但当前环境未执行，不标记 PASS。
- 企业微信、推送绑定组、可选 `pushBindingId`、用户绑定和工单转派继续按后续设计迭代。
- `/alarm/alarmAdd` 仍保持历史空 2xx 和吞异常语义；本轮不改变兼容协议，测试以最终接收闭环判定。

## 8. 部署交付补充（2026-07-15）

- 新增 `doc/Alarm-Push运行配置与数据库同步说明-20260715.md`。
- 明确 Java 8、Nacos、MySQL、Redis、RabbitMQ 前置条件及必要开关。
- 明确先执行既有幂等迁移、再校验表/字段/索引、最后按 Push → Alarm 顺序发布。
- 本轮不新增 Push DDL，不直接修改配置数据；所有配置 CRUD 继续通过接口完成。
