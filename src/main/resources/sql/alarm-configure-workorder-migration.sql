-- 报警配置推送过滤与一警一单迁移脚本。
-- 执行目标：
-- 1. alarm_configure 增加推送过滤和工单模板字段。
-- 2. alarm_handle 仅增加 workorder_id，不增加过程字段。
-- 3. 新增 alarm_workorder 当前事实表，不新增流转历史表。
-- 4. 对已有 alarm_handle 月度分片表补齐 workorder_id 和一警一 handle 唯一约束。

DELIMITER $$

DROP PROCEDURE IF EXISTS add_column_if_missing $$
CREATE PROCEDURE add_column_if_missing(IN p_table varchar(128), IN p_column varchar(128), IN p_ddl text)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = p_table
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @ddl_sql = p_ddl;
    PREPARE stmt FROM @ddl_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS add_index_if_missing $$
CREATE PROCEDURE add_index_if_missing(IN p_table varchar(128), IN p_index varchar(128), IN p_ddl text)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = p_table
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = p_table AND index_name = p_index
  ) THEN
    SET @ddl_sql = p_ddl;
    PREPARE stmt FROM @ddl_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS migrate_alarm_handle_tables $$
CREATE PROCEDURE migrate_alarm_handle_tables()
BEGIN
  DECLARE done int DEFAULT 0;
  DECLARE v_table varchar(128);
  DECLARE v_msg varchar(128);

  DECLARE cur CURSOR FOR
    SELECT table_name
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND (table_name = 'alarm_handle' OR table_name REGEXP '^alarm_handle_[0-9]{6}_[0-9]{2}$')
    ORDER BY table_name;

  DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

  OPEN cur;
  read_loop: LOOP
    FETCH cur INTO v_table;
    IF done = 1 THEN
      LEAVE read_loop;
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = v_table AND column_name = 'workorder_id'
    ) THEN
      SET @ddl_sql = CONCAT(
        'ALTER TABLE `', v_table,
        '` ADD COLUMN `workorder_id` bigint NULL COMMENT ''关联工单ID，直接处理为空，工单处理完成时填写'' AFTER `alarm_id`'
      );
      PREPARE stmt FROM @ddl_sql;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
    END IF;

    SET @dup_count = 0;
    SET @dup_sql = CONCAT(
      'SELECT COUNT(*) INTO @dup_count FROM (SELECT alarm_id FROM `',
      v_table,
      '` GROUP BY alarm_id HAVING COUNT(*) > 1 LIMIT 1) t'
    );
    PREPARE stmt FROM @dup_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;

    IF @dup_count > 0 THEN
      SET v_msg = CONCAT('duplicate alarm_id in ', v_table);
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_msg;
    END IF;

    IF NOT EXISTS (
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = v_table AND index_name = 'uk_alarm_handle_alarm_id'
    ) THEN
      SET @ddl_sql = CONCAT(
        'ALTER TABLE `', v_table,
        '` ADD UNIQUE KEY `uk_alarm_handle_alarm_id` (`alarm_id`)'
      );
      PREPARE stmt FROM @ddl_sql;
      EXECUTE stmt;
      DEALLOCATE PREPARE stmt;
    END IF;
  END LOOP;
  CLOSE cur;
END $$

DROP PROCEDURE IF EXISTS normalize_alarm_workorder_assignee $$
CREATE PROCEDURE normalize_alarm_workorder_assignee()
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'alarm_workorder'
      AND column_name = 'assignee_id'
      AND (is_nullable <> 'YES' OR column_default IS NOT NULL)
  ) THEN
    ALTER TABLE `alarm_workorder`
      MODIFY COLUMN `assignee_id` bigint NULL DEFAULT NULL
      COMMENT '督促目标用户ID；NULL不推送、0接收组、正数定向用户';
  END IF;
END $$

DELIMITER ;

CALL add_column_if_missing('alarm_configure', 'push_enabled',
  'ALTER TABLE `alarm_configure` ADD COLUMN `push_enabled` char(1) NOT NULL DEFAULT ''1'' COMMENT ''是否进入报警推送：0不推送 1推送'' AFTER `repeat_cycle_number`');

CALL add_column_if_missing('alarm_configure', 'push_message_type',
  'ALTER TABLE `alarm_configure` ADD COLUMN `push_message_type` varchar(30) NULL COMMENT ''推送策略编码，对应推送配置 message_type；为空时回退 alarm_type'' AFTER `push_enabled`');

CALL add_column_if_missing('alarm_configure', 'workorder_push_message_type',
  'ALTER TABLE `alarm_configure` ADD COLUMN `workorder_push_message_type` varchar(30) NULL COMMENT ''报警工单推送策略编码，对应推送配置 message_type；为空表示创建工单后不推送'' AFTER `push_message_type`');

CALL add_column_if_missing('alarm_configure', 'workorder_config_id',
  'ALTER TABLE `alarm_configure` ADD COLUMN `workorder_config_id` bigint NULL COMMENT ''关联报警工单模板ID，为空表示确认后不能按模板创建工单'' AFTER `workorder_push_message_type`');

CREATE TABLE IF NOT EXISTS `alarm_workorder` (
  `workorder_id` bigint NOT NULL AUTO_INCREMENT COMMENT '工单ID',
  `workorder_no` varchar(50) NOT NULL COMMENT '工单编号',
  `alarm_id` bigint NOT NULL COMMENT '报警ID',
  `workorder_config_id` bigint NULL COMMENT '来源工单模板ID',
  `status` char(2) NOT NULL DEFAULT '0' COMMENT '工单状态：0待处理 1处理中 2已完成 3已关闭 4退回',
  `assignee_id` bigint NULL DEFAULT NULL COMMENT '督促目标用户ID；NULL不推送、0接收组、正数定向用户',
  `assignee_name` varchar(100) NULL COMMENT '定向督促目标名称',
  `title` varchar(200) NULL COMMENT '工单标题',
  `content` varchar(1000) NULL COMMENT '工单内容',
  `handle_result` varchar(500) NULL COMMENT '工单处理结果',
  `tenant_id` bigint NULL COMMENT '租户ID',
  `del_flag` char(2) DEFAULT '0' COMMENT '逻辑删除：0存在 2删除',
  `create_by` varchar(64) NULL COMMENT '创建人',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_by` varchar(64) NULL COMMENT '更新人',
  `update_time` datetime NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`workorder_id`),
  UNIQUE KEY `uk_alarm_workorder_alarm` (`alarm_id`),
  UNIQUE KEY `uk_alarm_workorder_no` (`workorder_no`),
  KEY `idx_alarm_workorder_status` (`tenant_id`, `status`, `create_time`),
  KEY `idx_alarm_workorder_assignee` (`assignee_id`, `status`),
  KEY `idx_alarm_workorder_tenant_assignee_status` (`tenant_id`, `assignee_id`, `status`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警工单表';

CALL normalize_alarm_workorder_assignee();

CALL add_index_if_missing('alarm_workorder', 'idx_alarm_workorder_tenant_assignee_status',
  'ALTER TABLE `alarm_workorder` ADD KEY `idx_alarm_workorder_tenant_assignee_status` (`tenant_id`, `assignee_id`, `status`, `create_time`)');

CALL add_index_if_missing('alarm_configure', 'idx_alarm_configure_resolve',
  'ALTER TABLE `alarm_configure` ADD KEY `idx_alarm_configure_resolve` (`tenant_id`, `scene_type`, `device_alarm_control`, `del_flag`, `alarm_type`)');

CALL add_index_if_missing('alarm_device_configure', 'idx_alarm_device_configure_cfg_sn',
  'ALTER TABLE `alarm_device_configure` ADD KEY `idx_alarm_device_configure_cfg_sn` (`alarm_configure_id`, `device_sn`)');

CALL add_index_if_missing('alarm_device_configure', 'idx_alarm_device_configure_sn_cfg',
  'ALTER TABLE `alarm_device_configure` ADD KEY `idx_alarm_device_configure_sn_cfg` (`device_sn`, `alarm_configure_id`)');

CALL migrate_alarm_handle_tables();

DROP PROCEDURE IF EXISTS migrate_alarm_handle_tables;
DROP PROCEDURE IF EXISTS normalize_alarm_workorder_assignee;
DROP PROCEDURE IF EXISTS add_index_if_missing;
DROP PROCEDURE IF EXISTS add_column_if_missing;
