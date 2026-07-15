-- Alarm Push API 环境检查（MySQL 8.x）
-- 本脚本只读检查，不创建、不更新、不删除任何配置数据。
-- 报警配置必须通过 /configure/add 或 /configure/update 创建和修改。
-- push 配置必须通过 /pushConfig/add 或 /pushConfig/update 创建和修改。

SET NAMES utf8mb4;

SET @tenant_id = 990010;
SET @workorder_config_id = 900;

SELECT 'required_schema' AS item, table_schema, table_name
FROM information_schema.tables
WHERE table_schema IN ('hpis_system', 'hpis_alarm', 'hpis_push')
  AND table_name IN (
      'sys_dict_data',
      'alarm_configure',
      'alarm_device_configure',
      'alarm_cid_index',
      'alarm_workorder',
      'active_push_config',
      'pushconfigid_devicesn',
      'push_message_log'
  )
ORDER BY table_schema, table_name;

SELECT 'alarm_configure_required_column' AS item, column_name
FROM information_schema.columns
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_configure'
  AND column_name IN (
      'push_enabled',
      'push_message_type',
      'workorder_push_message_type',
      'workorder_config_id'
  )
ORDER BY column_name;

SELECT 'alarm_type_10_dict' AS item, COUNT(*) AS existing_rows
FROM hpis_system.sys_dict_data
WHERE dict_type = 'alarm_type'
  AND dict_value = '10';

SELECT 'workorder_config_id_env_value' AS item,
       @workorder_config_id AS workorder_config_id,
       '本阶段工单模板先作为环境前置；测试包只通过 /configure 关联该 ID，不通过 SQL 创建模板。' AS note;

SELECT 'existing_test_alarm_config' AS item,
       alarm_configure_id,
       alarm_configure_name,
       alarm_type,
       tenant_id,
       push_enabled,
       push_message_type,
       workorder_push_message_type,
       workorder_config_id
FROM hpis_alarm.alarm_configure
WHERE tenant_id = @tenant_id
  AND alarm_configure_name LIKE 'api-push-e2e-%'
ORDER BY alarm_configure_name;

SELECT 'existing_test_push_config' AS item,
       active_push_config_id,
       config_name,
       message_type,
       push_channel_type,
       enabled,
       push_address,
       tenant_id
FROM hpis_push.active_push_config
WHERE config_name LIKE 'api-push-e2e-%'
ORDER BY config_name;
