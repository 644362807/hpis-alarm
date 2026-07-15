# Alarm / Push 运行配置与数据库同步说明（2026-07-15）

## 1. 适用范围

本文用于部署并验证本轮“报警配置 → 报警入库 → Push 路由 → 通道接收”基线修复。

- Alarm 服务：`hpis-alarm`，默认端口 `8806`。
- Push 服务：`hpis-push`，默认端口 `8812`。
- 本轮数据库变更只复用已有幂等迁移脚本，不新增 Push 表或字段。
- 企业微信、推送绑定组、`pushBindingId` 和工单转派推送属于后续迭代，不在本次发布范围内。

## 2. 运行前置条件

| 依赖 | 要求 |
|---|---|
| JDK | Java 8，并显式使用 UTF-8 |
| Nacos | Alarm、Push 能读取当前环境共享配置并完成服务注册 |
| MySQL | `hpis_alarm`、`hpis_push`、`hpis_system` 可用 |
| Redis | Alarm 设备配置缓存、Push 配置路由可读写 |
| RabbitMQ | `alarm_queue` 与 Push 动态配置队列可创建、消费 |
| 设备服务 | 新增或修改下发设备规则时可查询 `deviceIds` 对应设备，且设备属于当前租户 |

不得把数据库、Redis、RabbitMQ 密码写入仓库。连接信息继续由 Nacos 的
`application-${spring.profiles.active}.yml` 或部署平台密钥配置提供。

## 3. 必要运行配置

Alarm 在当前环境的 Nacos 配置中至少确认以下开关：

```yaml
push:
  # 总推送开关；生产闭环需要开启。
  open: true

alarm:
  push:
    # 默认只允许匹配到当前租户报警配置的报警进入配置推送。
    require-matched-config: true
  internal-test:
    # 仅联调隔离环境允许开启；生产必须关闭。
    remote-call-stub-enabled: false
```

配置语义：

- `push.open=false`：Alarm 不发送报警或工单推送消息，可作为推送总回滚开关。
- `alarm.push.require-matched-config=true`：按当前租户、场景、设备和报警类型匹配配置；未匹配时不发送 `push.alarm`。
- `alarm.push.require-matched-config=false`：仅用于短时兼容回滚，会恢复无匹配配置也可能推送的旧行为。
- `alarm.internal-test.*` 只能用于本地测试，不得用于生产绕过真实服务调用。

Nacos 共享配置还必须提供当前环境的 MySQL、Redis、RabbitMQ 连接参数。发布前分别从
Alarm 和 Push 实例确认连接池建立成功、Redis 无认证错误、RabbitMQ listener 已启动。

## 4. 数据库同步

### 4.1 执行原则

1. 先备份 `hpis_alarm`，并记录当前数据库版本和执行人。
2. 在 `hpis_alarm` 库执行幂等脚本：
   `src/main/resources/sql/alarm-configure-workorder-migration.sql`。
3. 脚本成功后执行本节校验 SQL；校验未通过不得启动新版本 Alarm。
4. 本轮 Push 代码不要求新增表或字段，因此不得为了本次发布临时创建另一套 Push 表。
5. 配置数据的增删改查仍必须走 Alarm/Push 接口；数据库脚本只负责结构迁移和发布校验。

MySQL 命令示例（账号和主机按环境替换）：

```powershell
mysql --host=<mysql-host> --user=<deploy-user> --password --database=hpis_alarm `
  --default-character-set=utf8mb4 `
  --execute="source D:/studyProject/hpis2.0/hpis/hpis-alarm/src/main/resources/sql/alarm-configure-workorder-migration.sql"
```

该脚本可重复执行，主要同步：

- `alarm_configure` 的 `push_enabled`、`push_message_type`、
  `workorder_push_message_type`、`workorder_config_id`；
- `alarm_workorder` 当前事实表；
- `alarm_handle` 及其月度分片的 `workorder_id` 和一警一处理唯一约束；
- Alarm 配置解析和设备绑定所需索引。

### 4.2 结构校验

```sql
SELECT table_schema, table_name
FROM information_schema.tables
WHERE (table_schema = 'hpis_alarm' AND table_name IN
       ('alarm_configure', 'alarm_device_configure', 'alarm_workorder'))
   OR (table_schema = 'hpis_push' AND table_name IN
       ('active_push_config', 'pushconfigid_devicesn', 'push_message_log'))
ORDER BY table_schema, table_name;

SELECT column_name
FROM information_schema.columns
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_configure'
  AND column_name IN
      ('push_enabled', 'push_message_type', 'workorder_push_message_type', 'workorder_config_id')
ORDER BY column_name;

SELECT index_name, column_name
FROM information_schema.statistics
WHERE table_schema = 'hpis_alarm'
  AND table_name IN ('alarm_configure', 'alarm_device_configure')
  AND index_name IN
      ('idx_alarm_configure_resolve', 'idx_alarm_device_configure_cfg_sn',
       'idx_alarm_device_configure_sn_cfg')
ORDER BY table_name, index_name, seq_in_index;
```

预期结果：6 张基础表均存在，4 个 Alarm 配置字段均存在，3 个配置解析索引均存在。

### 4.3 数据兼容校验

```sql
-- 新增 Push 配置由接口显式写入 del_flag='0'；历史 NULL 数据仍兼容读取。
SELECT del_flag, COUNT(*) AS row_count
FROM hpis_push.active_push_config
GROUP BY del_flag;

-- 检查同一配置和设备是否存在重复绑定。
SELECT active_push_config_id, device_sn, COUNT(*) AS duplicate_count
FROM hpis_push.pushconfigid_devicesn
GROUP BY active_push_config_id, device_sn
HAVING COUNT(*) > 1;

-- 检查报警配置是否存在跨租户误关联迹象，结果需结合设备主数据复核。
SELECT tenant_id, alarm_configure_id, alarm_configure_name, alarm_type, del_flag
FROM hpis_alarm.alarm_configure
WHERE del_flag = '0'
ORDER BY tenant_id, alarm_configure_id;
```

本轮不强制把历史 `active_push_config.del_flag IS NULL` 更新为 `0`：新代码同时兼容 `0` 和
`NULL`，新建记录统一写 `0`。如后续要清洗历史数据，应作为独立数据治理任务，先备份、统计影响行数再执行。

## 5. 构建与启动

在父工程目录 `D:\studyProject\hpis2.0\hpis` 构建：

```powershell
$env:JAVA_HOME = '<jdk8-home>'
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
mvn -pl hpis-alarm -am -DskipTests package
mvn -pl hpis-push -am -DskipTests package
```

建议先启动 Push，再启动 Alarm，避免 Alarm 已产生消息但 Push 路由尚未恢复：

```powershell
java -Dfile.encoding=UTF-8 -jar hpis-push/target/hpis-push.jar `
  --spring.profiles.active=<profile>

java -Dfile.encoding=UTF-8 -jar hpis-alarm/target/hpis-alarm.jar `
  --spring.profiles.active=<profile> `
  --push.open=true `
  --alarm.push.require-matched-config=true
```

Push 冷启动后以数据库中当前租户可见的启用配置为事实源，重建 Redis 路由、动态队列和
HTTP/MQTT Consumer。确认恢复完成后再放入 Alarm 流量。

## 6. 发布后验证

1. 调用 Alarm 配置接口完成新增、列表、详情、修改、删除，确认只能操作当前租户数据。
2. 调用 Push 配置接口完成新增、列表、详情、启用、禁用、删除，确认只能操作当前租户数据。
3. 通过 HTTP `/alarm/alarmAdd` 和真实 RabbitMQ `alarm_queue` 各发送一次报警。
4. 确认匹配配置时收到最终 HTTP/MQTT/WebSocket 消息；禁用 Push 配置后不再投递。
5. 确认所有测试配置通过接口删除，再用只读 SQL 检查运行数据和推送日志。

可复用执行入口：

```powershell
powershell -ExecutionPolicy Bypass `
  -File hpis-alarm/src/test/resources/scripts/run-alarm-push-e2e.ps1
```

完整接口实例、请求响应和当前实测结果见 `doc/报警Push接口测试用例-全新环境.md`。

## 7. 回滚顺序

1. 先停止 Alarm 流量或设置 `push.open=false`，避免回滚期间继续产生推送。
2. 回滚 Alarm 应用版本，再回滚 Push 应用版本。
3. 本次迁移新增字段和表保持向后兼容，应用回滚时不建议删除字段、索引或历史数据。
4. 若只需恢复旧的“无配置也推送”行为，可临时设置
   `alarm.push.require-matched-config=false`，无需回滚数据库。
