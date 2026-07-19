# Alarm-Push 测试交付包

## 1. 适用人员

本文档包面向不了解 Alarm、Push 内部实现的接口测试人员。测试目标是独立走完“运行环境准备 → 报警配置 → 报警上报 → 报警记录 → 查看与处理 → 工单 → Push 路由 → 最终接收人/接收端 → 数据清理”的完整流程。

交付包生成日期：2026-07-19。

## 2. 开始前必须准备

- 可访问 Alarm、Push、Nacos、MySQL、Redis 和 RabbitMQ 的测试环境。
- 当前租户有效 Token，并具备报警、处理、工单、Push 配置权限。
- 当前租户至少三个可用于测试的设备 ID、设备 SN 和网关 SN。
- 一个有效的工单模板 ID。
- 如测试企业微信，准备测试企业微信应用、三个测试用户及其平台用户绑定。
- 仅使用测试租户和测试数据，不在生产环境执行清理 SQL。

## 3. 推荐执行顺序

1. 阅读[运行配置与 SQL 同步说明](docs/03-运行配置与SQL同步说明.md)，完成 Nacos、数据库结构和服务启动准备。
2. 在测试库执行[环境预检 SQL](sql/alarm-push-api-setup.sql)。该脚本只读，不会创建或修改配置。
3. 阅读[Postman 导入与执行说明](docs/04-Postman导入与执行说明.md)，依次导入 Collection 和 Environment。
4. 按[全流程测试使用手册](docs/01-全流程测试使用手册.md)完成报警配置、报警上传、记录查询、处理、工单和 Push 测试。
5. 遇到接口字段、枚举或输入输出疑问时查阅[API 接口文档](docs/02-API接口文档.md)。
6. 执行[测试证据查询 SQL](sql/alarm-push-api-check.sql)，保存报警记录、工单和 Push 日志证据。
7. 先通过 API 删除本轮 Alarm/Push 配置；获得清理授权并确认测试标识后，才可执行[运行数据清理 SQL](sql/alarm-push-api-cleanup.sql)。

## 4. 目录说明

| 文件 | 用途 | 是否可直接执行 |
|---|---|---|
| `docs/01-全流程测试使用手册.md` | 零基础端到端操作、期望结果、PASS/FAIL/BLOCKED 判定 | 按步骤操作 |
| `docs/02-API接口文档.md` | Alarm、Handle、Workorder、Push、企业微信接口与字段字典 | 查询参考 |
| `docs/03-运行配置与SQL同步说明.md` | Nacos 配置、服务启动、生产迁移脚本顺序、回滚和检查 | 先阅读再操作 |
| `docs/04-Postman导入与执行说明.md` | Collection/Environment 导入、变量来源和执行顺序 | 按步骤操作 |
| `postman/hpis-alarm-push.postman_collection.json` | HTTP 报警 10、工单 25、失败场景和清理请求 | 导入 Postman/Apifox |
| `postman/hpis-alarm-push.postman_environment.json` | 测试地址、Token、租户、设备及运行变量 | 导入后必须修改 |
| `sql/alarm-push-api-setup.sql` | 测试前结构、字典、模板和残留配置检查 | 只读，可在测试库执行 |
| `sql/alarm-push-api-check.sql` | 测试后报警、工单、Push 日志证据查询 | 只读，可在测试库执行 |
| `sql/alarm-push-api-cleanup.sql` | 删除本轮报警运行数据和 Push 日志 | 有删除操作，需授权 |

## 5. Postman 自动化覆盖边界

Postman Collection 自动覆盖：

- Alarm 配置新增、列表、详情和删除；
- HTTP Push 配置 `messageType=10`；
- 工单 Push 配置 `messageType=25`；
- HTTP 报警上传、最终 HTTP 接收器验证；
- 报警确认、工单创建、重复工单失败；
- Push 禁用、不可达地址和缺失字段等失败场景；
- 测试配置和接收器事件清理。

Postman Collection不覆盖企业微信应用、平台用户绑定、接收组、普通报警收件人、工单负责人和转派收件人测试。企业微信必须按照全流程测试手册执行并保留真实收件截图或消息 ID；不能把 Collection 运行完成判定为企业微信 PASS。

## 6. SQL 安全边界

- `alarm-push-api-setup.sql` 和 `alarm-push-api-check.sql` 是只读脚本。
- `alarm-push-api-cleanup.sql` 包含 `DELETE`，只允许清理本轮明确测试标识对应的数据。
- Alarm 配置和 Push 配置必须通过接口增删改查，禁止使用 SQL 代替配置 API。
- 生产结构迁移 SQL 不复制到本交付包。正式同步时以仓库 `src/main/resources/sql/` 为唯一事实来源，并严格遵守运行配置与 SQL 同步说明的顺序和预检条件。
- 执行任何 DDL 或清理 SQL 前都要确认数据库、租户、备份和回滚条件。

## 7. 最终通过标准

只有同时满足以下条件，整体流程才可判定 PASS：

1. 报警配置可新增、查询、修改和删除，且设备、场景、报警类型和推送消息类型匹配。
2. 报警上传后能按外部 `alarmCid` 回查到内部 Long `alarmId`，不能只依据 HTTP 2xx。
3. 报警记录查看、修改、处理、停止和删除符合接口文档的状态语义。
4. 工单创建、转派、完成和重复创建判定符合手册要求。
5. HTTP、企业微信或其他实际启用通道到达最终接收端；只进入 MQ 不算最终推送成功。
6. 企业微信普通报警到达接收组成员，工单创建和转派到达当前负责人。
7. 只读 SQL 证据与接口、接收器或收件截图相互一致。
8. 本轮配置、测试运行数据和接收器事件已按授权清理，且未影响其他租户数据。

任一依赖不可用但测试步骤本身没有失败时标记 BLOCKED，并记录缺少的服务、权限、凭证或测试数据；不得写成 PASS。
