# 旧 0～4 分表纯 SQL 迁移说明

## 迁移结果

迁移过程不修改 Java 代码。旧 `alarm_0`～`alarm_4` 及其处理表、扩展表保持不变；数据按照报警时间和容量导入新的 `yyyyMM_nn` 物理表，旧 `alarm_id` 转换成当前 `AlarmIdCodec` 可直接路由的新 ID。

数据库保留 `alarm_legacy_id_migration_map` 作为迁移审计记录，应用不读取该表。执行器还会在操作电脑上生成一份包含全部新旧 ID 对照关系的 CSV 文件。

## 执行前

1. 备份现场数据库，并在数据库副本完成一次演练。
2. 确认 MySQL 版本为 8.0 或更高。
3. 先执行 `alarm-time-capacity-sharding.sql`，确保 `alarm_shard_slice`、`alarm_cid_index` 和 `alarm_cid_stale_index` 已创建。
4. 最终迁移时停止旧应用写入；新分片应用尚不能开放写入。
5. 临时任务表中不能存在引用旧 ID 的 stop side effect 或电解槽快照命令。

## 只执行预检

```powershell
powershell -ExecutionPolicy Bypass -File .\src\main\resources\scripts\run-alarm-legacy-0-4-id-rewrite-migration.ps1 `
  -Database hpis_alarm `
  -HostName 127.0.0.1 `
  -Port 3306 `
  -User root `
  -Password "数据库密码" `
  -PrecheckOnly
```

预检不会复制业务数据，也不会生成 CSV。

## 正式迁移并导出完整映射

```powershell
powershell -ExecutionPolicy Bypass -File .\src\main\resources\scripts\run-alarm-legacy-0-4-id-rewrite-migration.ps1 `
  -Database hpis_alarm `
  -HostName 127.0.0.1 `
  -Port 3306 `
  -User root `
  -Password "数据库密码" `
  -MaxRows 5000000 `
  -WorkerId 0 `
  -HotHours 24 `
  -StaleExpireDays 30 `
  -OutputFile "D:\migration-output\alarm-id-mapping.csv"
```

只有迁移批次状态为 `PASS`，并且 CSV 行数与数据库映射表行数完全一致时，执行器才报告成功。

CSV 包含：

- 旧、新 `alarm_id`
- 旧来源表
- 旧、新 `alarm_handle_id`
- 原始报警时间和实际路由时间
- 月份、切片号、切片内行号和目标表后缀
- 迁移状态、迁移批次和创建时间

## 幂等与回滚

- 同一个旧 ID 第一次生成映射后永久保持不变，重复执行不会重新分配。
- 目标表只允许包含映射表记录；发现新应用已经写入目标表时脚本会中止。
- 旧分片表不删除，可用于核对和迁移前回退。
- `alarm_electrolytic_cell_ectype` 和 `alarm_workorder` 更新前分别备份到 `alarm_legacy_backup_*`。
- 新应用开放写入后再回退旧应用，会产生双向数据差异，不能只靠改配置完成无损回滚。

## 验收

1. `alarm_legacy_migration_run` 最新批次为 `PASS`。
2. `alarm_legacy_migration_audit` 当前批次全部为 `PASS`。
3. CSV 行数等于五张旧报警表记录总数。
4. 随机抽查 CSV 中的新 ID，能解析到 CSV 记录的 `table_suffix`。
5. 按新 ID、时间范围、未关闭 `alarm_cid` 分别进行查询和消警验证。
