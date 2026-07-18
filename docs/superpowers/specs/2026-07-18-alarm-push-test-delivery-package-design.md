# Alarm-Push 测试交付包整理设计

## 1. 目标

把 Alarm 到 Push 全流程测试所需的使用文档、API 文档、运行配置与 SQL 同步说明、Postman 导入说明及配套资源整理为一个可独立交付的目录，使不了解系统的测试人员能够按统一入口完成环境准备、接口导入、数据检查和全流程测试。

完成后继续使用现有分支 `codex/alarm-push-test-docs`，提交并同步到 PR #1。

## 2. 交付目录

目标目录为 `doc/Alarm-Push测试交付包/`：

```text
Alarm-Push测试交付包/
├─ README.md
├─ docs/
│  ├─ 01-全流程测试使用手册.md
│  ├─ 02-API接口文档.md
│  ├─ 03-运行配置与SQL同步说明.md
│  └─ 04-Postman导入与执行说明.md
├─ postman/
│  ├─ hpis-alarm-push.postman_collection.json
│  └─ hpis-alarm-push.postman_environment.json
└─ sql/
   ├─ alarm-push-api-setup.sql
   ├─ alarm-push-api-check.sql
   └─ alarm-push-api-cleanup.sql
```

## 3. 文件职责与来源

| 交付文件 | 来源或生成方式 | 职责 |
|---|---|---|
| `README.md` | 新增 | 提供阅读顺序、执行顺序、文件边界和风险提示 |
| `docs/01-全流程测试使用手册.md` | 复制 `doc/Alarm-Push-全流程测试使用手册.md` | 面向零基础测试人员给出完整业务测试流程 |
| `docs/02-API接口文档.md` | 复制 `doc/Alarm-Push-API接口文档.md` | 提供接口、字段、枚举、输入输出和路由规则 |
| `docs/03-运行配置与SQL同步说明.md` | 复制当前工作区 `doc/Alarm-Push运行配置与数据库同步说明-20260715.md` | 说明 Nacos 配置、服务启动、数据库迁移顺序和回滚 |
| `docs/04-Postman导入与执行说明.md` | 新增 | 说明两个 JSON 的导入顺序、变量来源、执行顺序和结果判定 |
| `postman/*.json` | 复制 `src/test/resources/postman/` 下两个文件 | 提供可直接导入的 Collection 和 Environment |
| `sql/*.sql` | 复制 `src/test/resources/sql/alarm-push-api-*.sql` | 提供测试环境预检、只读证据检查和受控清理 |

原文件全部保留，避免改变 Maven 测试资源路径、现有文档链接和历史使用方式。交付目录是一个明确版本的测试快照，不替代源码资源目录。

## 4. 使用顺序

测试人员按以下顺序使用交付包：

1. 阅读 `README.md`，确认环境、权限和风险边界。
2. 阅读运行配置与 SQL 同步说明，完成 Nacos 配置、基础库和结构迁移准备。
3. 执行 `sql/alarm-push-api-setup.sql` 做测试前只读检查。
4. 按 Postman 导入说明依次导入 Collection 和 Environment，并填写真实环境变量。
5. 按全流程测试手册执行报警配置、报警上报、记录查看、处理、工单和 Push 验证。
6. 需要字段或接口细节时查阅 API 文档。
7. 使用 `sql/alarm-push-api-check.sql` 保存只读数据库证据。
8. 先通过接口删除配置，再经明确授权使用 `sql/alarm-push-api-cleanup.sql` 清理本轮运行数据。

## 5. Postman 边界

现有 Collection 覆盖以下自动化请求：

- Alarm 配置新增、列表、详情和删除；
- Push HTTP 配置 `messageType=10` 与工单配置 `messageType=25`；
- HTTP 报警上传、最终接收器检查；
- 报警确认、工单创建、重复工单失败；
- Push 禁用、不可达地址、字段缺失等失败场景；
- 本轮配置与接收器事件清理。

Collection 不覆盖企业微信应用、用户绑定和接收组接口。企业微信和工单指定收件人测试继续按全流程测试手册执行，不得因为 Postman 集合运行结束就判定企业微信链路通过。

## 6. SQL 安全边界

- `alarm-push-api-setup.sql` 是测试前只读检查，不创建或修改配置。
- `alarm-push-api-check.sql` 是测试证据查询，只读使用。
- `alarm-push-api-cleanup.sql` 包含删除运行数据的语句，只能在确认租户、运行标识和备份条件后执行。
- Alarm 配置和 Push 配置必须通过 API 增删改查，SQL 不代替配置接口。
- 生产结构迁移脚本仍以 `src/main/resources/sql/` 为唯一事实来源，本交付包不复制生产迁移 SQL，避免出现两个可执行版本。

## 7. 未提交源文档处理

`doc/Alarm-Push运行配置与数据库同步说明-20260715.md` 当前存在大段未提交完善内容。交付包复制当前工作区的最新内容，但不修改、暂存或提交该原文件，从而保留原有工作区归属并避免覆盖其他人的修改。

## 8. 验证

归档完成后执行以下验证：

1. 两个 Postman JSON 均能通过 JSON 解析。
2. Collection 中所有 `{{variable}}` 都能在 Environment 或执行期捕获变量中找到来源。
3. 三个 SQL 文件与源资源逐字节一致。
4. 两份既有 Alarm-Push 文档与源文档逐字节一致。
5. 配置与 SQL 同步说明与当前工作区版本逐字节一致。
6. 新增 Markdown 文档不存在 `TODO`、`TBD`、无效相对链接或真实密钥。
7. `git diff --check` 通过，提交只包含设计文件和目标交付目录。

## 9. 提交与同步

实施提交只暂存目标交付目录，不暂存仓库内其他已有修改。验证通过后推送 `codex/alarm-push-test-docs`，现有 PR #1 自动更新，并补充 PR 描述说明测试交付包的入口和 Postman 覆盖边界。
