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
      'alarm_handle',
      'alarm_workorder',
      'active_push_config',
      'pushconfigid_devicesn',
      'push_message_log',
      'push_wecom_app_config',
      'push_wecom_user_binding',
      'push_recipient_group',
      'push_recipient_group_member'
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

SELECT 'workorder_required_column' AS item, table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'hpis_alarm'
  AND ((table_name = 'alarm_workorder' AND column_name IN
        ('workorder_id', 'alarm_id', 'workorder_config_id', 'status', 'assignee_id',
         'handle_result', 'tenant_id', 'del_flag', 'create_time'))
    OR (table_name LIKE 'alarm_handle%' AND column_name IN
        ('alarm_id', 'workorder_id', 'handler_id', 'handler_name', 'opinion', 'handle_picture')))
ORDER BY table_name, column_name;

SELECT 'workorder_query_index' AS item, index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_workorder'
  AND index_name = 'idx_alarm_workorder_tenant_assignee_status'
ORDER BY seq_in_index;

SELECT 'workorder_picture_must_not_exist' AS item, COUNT(*) AS unexpected_columns
FROM information_schema.columns
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_workorder'
  AND column_name = 'handle_picture';

SELECT 'push_wecom_required_column' AS item, table_name, column_name
FROM information_schema.columns
WHERE table_schema = 'hpis_push'
  AND ((table_name = 'active_push_config' AND column_name IN ('route_scope', 'recipient_group_id'))
    OR (table_name = 'push_wecom_app_config' AND column_name IN
        ('tenant_id', 'corp_id', 'agent_id', 'corp_secret_ciphertext', 'enabled'))
    OR (table_name = 'push_wecom_user_binding' AND column_name IN
        ('tenant_id', 'user_id', 'wecom_user_id', 'enabled'))
    OR (table_name = 'push_recipient_group' AND column_name IN
        ('id', 'tenant_id', 'group_name', 'enabled'))
    OR (table_name = 'push_recipient_group_member' AND column_name IN
        ('tenant_id', 'group_id', 'user_id')))
ORDER BY table_name, column_name;

SELECT 'alarm_type_10_dict' AS item, COUNT(*) AS existing_rows
FROM hpis_system.sys_dict_data
WHERE dict_type = 'alarm_type'
  AND dict_value = '10';

SELECT 'workorder_config_id_env_value' AS item,
       @workorder_config_id AS workorder_config_id,
       '当前仓库无工单模板 CRUD/引用校验；该正数仅作为 /configure 的工单启用关联值，不代表模板实体已验证。' AS note;

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

SELECT 'existing_test_recipient_group' AS item,
       g.id AS group_id, g.group_name, g.enabled, g.tenant_id, m.user_id
FROM hpis_push.push_recipient_group g
LEFT JOIN hpis_push.push_recipient_group_member m
  ON m.tenant_id = g.tenant_id AND m.group_id = g.id
WHERE g.tenant_id = @tenant_id
  AND g.group_name LIKE 'api-push-e2e-%'
ORDER BY g.id, m.user_id;
