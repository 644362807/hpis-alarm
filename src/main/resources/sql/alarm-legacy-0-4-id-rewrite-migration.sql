-- 旧 alarm_0..4 到 yyyyMM_nn 的纯 SQL 迁移（重写 alarm_id）
-- MySQL 8.0+。
--
-- 边界：
-- 1. 不修改应用代码，不创建运行时旧 ID 路由。
-- 2. 旧 alarm_0..4、alarm_handle_0..4、alarm_electrolytic_cell_0..4 始终保留。
-- 3. alarm_legacy_id_migration_map 是迁移审计记录，不参与应用路由；CSV 从该表导出。
-- 4. 执行最终迁移前必须停止旧应用写入，并确认新分片尚未开放写入。
-- 5. 当前应用的历史表扫描只识别两位 slice，因此本脚本限制每月最多 00..99 共 100 个切片。
--
-- 执行：
-- CALL alarm_run_legacy_0_4_id_rewrite_migration(5000000, 0, 24, 30);
-- 参数依次为：单表最大行数、迁移 workerId、hot 小时数、stale 保留天数。

CREATE TABLE IF NOT EXISTS alarm_legacy_id_migration_map (
  old_alarm_id bigint NOT NULL COMMENT '旧 alarm_id',
  new_alarm_id bigint NOT NULL COMMENT '符合 AlarmIdCodec v2 的新 alarm_id',
  source_table_no tinyint NOT NULL COMMENT '旧分表号 0..4',
  source_table_name varchar(32) NOT NULL,
  source_alarm_beginTime datetime NULL,
  route_alarm_beginTime datetime NOT NULL COMMENT '实际用于分片和 ID 编码的时间',
  month_key char(6) NOT NULL,
  slice_no int NOT NULL,
  row_no bigint NOT NULL,
  table_suffix varchar(16) NOT NULL,
  max_rows bigint NOT NULL,
  old_alarm_handle_id bigint NULL,
  new_alarm_handle_id bigint NULL,
  migration_status varchar(16) NOT NULL DEFAULT 'PLANNED',
  created_run_id char(36) NOT NULL,
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (old_alarm_id),
  UNIQUE KEY uk_legacy_new_alarm_id (new_alarm_id),
  UNIQUE KEY uk_legacy_slice_row (month_key, slice_no, row_no),
  KEY idx_legacy_target (table_suffix),
  KEY idx_legacy_source (source_table_no, old_alarm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧报警ID到新报警ID的永久迁移审计映射';

CREATE TABLE IF NOT EXISTS alarm_legacy_migration_run (
  run_id char(36) NOT NULL,
  max_rows bigint NOT NULL,
  worker_id int NOT NULL,
  hot_hours int NOT NULL,
  stale_expire_days int NOT NULL,
  source_rows bigint NULL,
  mapped_rows bigint NULL,
  status varchar(16) NOT NULL,
  error_message varchar(1024) NULL,
  started_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_time datetime NULL,
  PRIMARY KEY (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧报警迁移执行批次';

CREATE TABLE IF NOT EXISTS alarm_legacy_migration_audit (
  id bigint NOT NULL AUTO_INCREMENT,
  run_id char(36) NOT NULL,
  table_suffix varchar(16) NOT NULL,
  entity_name varchar(32) NOT NULL,
  expected_rows bigint NOT NULL,
  actual_rows bigint NOT NULL,
  mismatch_rows bigint NOT NULL,
  audit_status varchar(16) NOT NULL,
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_legacy_audit_run (run_id, audit_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧报警迁移逐表校验结果';

DELIMITER $$

DROP PROCEDURE IF EXISTS alarm_legacy_add_column $$
CREATE PROCEDURE alarm_legacy_add_column(IN p_table varchar(64), IN p_column varchar(64), IN p_ddl text)
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND BINARY table_name = BINARY p_table
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND BINARY table_name = BINARY p_table
      AND BINARY column_name = BINARY p_column
  ) THEN
    SET @legacy_ddl = p_ddl;
    PREPARE legacy_stmt FROM @legacy_ddl;
    EXECUTE legacy_stmt;
    DEALLOCATE PREPARE legacy_stmt;
  END IF;
END $$

DELIMITER ;

CALL alarm_legacy_add_column('alarm_handle', 'workorder_id',
  'ALTER TABLE alarm_handle ADD COLUMN workorder_id bigint NULL AFTER alarm_id');
CALL alarm_legacy_add_column('alarm_handle', 'alarm_beginTime',
  'ALTER TABLE alarm_handle ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_electrolytic_cell', 'alarm_beginTime',
  'ALTER TABLE alarm_electrolytic_cell ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_electrolytic_cell', 'del_flag',
  'ALTER TABLE alarm_electrolytic_cell ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0''');
CALL alarm_legacy_add_column('alarm_partial_discharge', 'del_flag',
  'ALTER TABLE alarm_partial_discharge ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0''');

CALL alarm_legacy_add_column('alarm_handle_0', 'workorder_id',
  'ALTER TABLE alarm_handle_0 ADD COLUMN workorder_id bigint NULL AFTER alarm_id');
CALL alarm_legacy_add_column('alarm_handle_1', 'workorder_id',
  'ALTER TABLE alarm_handle_1 ADD COLUMN workorder_id bigint NULL AFTER alarm_id');
CALL alarm_legacy_add_column('alarm_handle_2', 'workorder_id',
  'ALTER TABLE alarm_handle_2 ADD COLUMN workorder_id bigint NULL AFTER alarm_id');
CALL alarm_legacy_add_column('alarm_handle_3', 'workorder_id',
  'ALTER TABLE alarm_handle_3 ADD COLUMN workorder_id bigint NULL AFTER alarm_id');
CALL alarm_legacy_add_column('alarm_handle_4', 'workorder_id',
  'ALTER TABLE alarm_handle_4 ADD COLUMN workorder_id bigint NULL AFTER alarm_id');
CALL alarm_legacy_add_column('alarm_handle_0', 'alarm_beginTime',
  'ALTER TABLE alarm_handle_0 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_handle_1', 'alarm_beginTime',
  'ALTER TABLE alarm_handle_1 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_handle_2', 'alarm_beginTime',
  'ALTER TABLE alarm_handle_2 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_handle_3', 'alarm_beginTime',
  'ALTER TABLE alarm_handle_3 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_handle_4', 'alarm_beginTime',
  'ALTER TABLE alarm_handle_4 ADD COLUMN alarm_beginTime datetime NULL');

CALL alarm_legacy_add_column('alarm_electrolytic_cell_0', 'alarm_beginTime',
  'ALTER TABLE alarm_electrolytic_cell_0 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_1', 'alarm_beginTime',
  'ALTER TABLE alarm_electrolytic_cell_1 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_2', 'alarm_beginTime',
  'ALTER TABLE alarm_electrolytic_cell_2 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_3', 'alarm_beginTime',
  'ALTER TABLE alarm_electrolytic_cell_3 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_4', 'alarm_beginTime',
  'ALTER TABLE alarm_electrolytic_cell_4 ADD COLUMN alarm_beginTime datetime NULL');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_0', 'del_flag',
  'ALTER TABLE alarm_electrolytic_cell_0 ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0''');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_1', 'del_flag',
  'ALTER TABLE alarm_electrolytic_cell_1 ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0''');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_2', 'del_flag',
  'ALTER TABLE alarm_electrolytic_cell_2 ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0''');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_3', 'del_flag',
  'ALTER TABLE alarm_electrolytic_cell_3 ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0''');
CALL alarm_legacy_add_column('alarm_electrolytic_cell_4', 'del_flag',
  'ALTER TABLE alarm_electrolytic_cell_4 ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0''');

DROP PROCEDURE alarm_legacy_add_column;

CREATE OR REPLACE VIEW alarm_legacy_source_v AS
SELECT 0 source_table_no, alarm_id old_alarm_id, device_sn, alarm_type, alarm_rank, alarm_status,
       scene_type, del_flag, alarm_beginTime source_alarm_beginTime,
       COALESCE(alarm_beginTime, create_time) route_alarm_beginTime,
       alarm_endTime, picture_path, video_picture, video_path, create_time, update_time,
       create_by, update_by, alarm_cid, irms_sn, area_sn, target_name, maxTemp, minTemp,
       tenant_id, remark_data
FROM alarm_0
UNION ALL
SELECT 1, alarm_id, device_sn, alarm_type, alarm_rank, alarm_status, scene_type, del_flag,
       alarm_beginTime, COALESCE(alarm_beginTime, create_time), alarm_endTime, picture_path,
       video_picture, video_path, create_time, update_time, create_by, update_by, alarm_cid,
       irms_sn, area_sn, target_name, maxTemp, minTemp, tenant_id, remark_data
FROM alarm_1
UNION ALL
SELECT 2, alarm_id, device_sn, alarm_type, alarm_rank, alarm_status, scene_type, del_flag,
       alarm_beginTime, COALESCE(alarm_beginTime, create_time), alarm_endTime, picture_path,
       video_picture, video_path, create_time, update_time, create_by, update_by, alarm_cid,
       irms_sn, area_sn, target_name, maxTemp, minTemp, tenant_id, remark_data
FROM alarm_2
UNION ALL
SELECT 3, alarm_id, device_sn, alarm_type, alarm_rank, alarm_status, scene_type, del_flag,
       alarm_beginTime, COALESCE(alarm_beginTime, create_time), alarm_endTime, picture_path,
       video_picture, video_path, create_time, update_time, create_by, update_by, alarm_cid,
       irms_sn, area_sn, target_name, maxTemp, minTemp, tenant_id, remark_data
FROM alarm_3
UNION ALL
SELECT 4, alarm_id, device_sn, alarm_type, alarm_rank, alarm_status, scene_type, del_flag,
       alarm_beginTime, COALESCE(alarm_beginTime, create_time), alarm_endTime, picture_path,
       video_picture, video_path, create_time, update_time, create_by, update_by, alarm_cid,
       irms_sn, area_sn, target_name, maxTemp, minTemp, tenant_id, remark_data
FROM alarm_4;

CREATE OR REPLACE VIEW alarm_legacy_handle_source_v AS
SELECT 0 source_table_no, alarm_handle_id old_alarm_handle_id, alarm_id old_alarm_id, workorder_id,
       handle_status, del_flag, identify, opinion, handler_id, handle_picture, create_time,
       create_by, update_time, update_by, handle_time, confirm_user_id, apparatus_id,
       handler_name, alarm_beginTime
FROM alarm_handle_0
UNION ALL
SELECT 1, alarm_handle_id, alarm_id, workorder_id, handle_status, del_flag, identify, opinion,
       handler_id, handle_picture, create_time, create_by, update_time, update_by, handle_time,
       confirm_user_id, apparatus_id, handler_name, alarm_beginTime
FROM alarm_handle_1
UNION ALL
SELECT 2, alarm_handle_id, alarm_id, workorder_id, handle_status, del_flag, identify, opinion,
       handler_id, handle_picture, create_time, create_by, update_time, update_by, handle_time,
       confirm_user_id, apparatus_id, handler_name, alarm_beginTime
FROM alarm_handle_2
UNION ALL
SELECT 3, alarm_handle_id, alarm_id, workorder_id, handle_status, del_flag, identify, opinion,
       handler_id, handle_picture, create_time, create_by, update_time, update_by, handle_time,
       confirm_user_id, apparatus_id, handler_name, alarm_beginTime
FROM alarm_handle_3
UNION ALL
SELECT 4, alarm_handle_id, alarm_id, workorder_id, handle_status, del_flag, identify, opinion,
       handler_id, handle_picture, create_time, create_by, update_time, update_by, handle_time,
       confirm_user_id, apparatus_id, handler_name, alarm_beginTime
FROM alarm_handle_4;

CREATE OR REPLACE VIEW alarm_legacy_cell_source_v AS
SELECT 0 source_table_no, alarm_id old_alarm_id, sequence_id, row_index, groove_number,
       subdivide_number, observation_place, temperature_variation, repeat_number, repeat_time,
       repeat_handler_users, repeat_handle_time, alarm_beginTime, del_flag
FROM alarm_electrolytic_cell_0
UNION ALL
SELECT 1, alarm_id, sequence_id, row_index, groove_number, subdivide_number, observation_place,
       temperature_variation, repeat_number, repeat_time, repeat_handler_users, repeat_handle_time,
       alarm_beginTime, del_flag
FROM alarm_electrolytic_cell_1
UNION ALL
SELECT 2, alarm_id, sequence_id, row_index, groove_number, subdivide_number, observation_place,
       temperature_variation, repeat_number, repeat_time, repeat_handler_users, repeat_handle_time,
       alarm_beginTime, del_flag
FROM alarm_electrolytic_cell_2
UNION ALL
SELECT 3, alarm_id, sequence_id, row_index, groove_number, subdivide_number, observation_place,
       temperature_variation, repeat_number, repeat_time, repeat_handler_users, repeat_handle_time,
       alarm_beginTime, del_flag
FROM alarm_electrolytic_cell_3
UNION ALL
SELECT 4, alarm_id, sequence_id, row_index, groove_number, subdivide_number, observation_place,
       temperature_variation, repeat_number, repeat_time, repeat_handler_users, repeat_handle_time,
       alarm_beginTime, del_flag
FROM alarm_electrolytic_cell_4;

DELIMITER $$

DROP PROCEDURE IF EXISTS alarm_assert_legacy_id_rewrite_ready $$
CREATE PROCEDURE alarm_assert_legacy_id_rewrite_ready(IN p_max_rows bigint, IN p_worker_id int)
BEGIN
  DECLARE v_count bigint DEFAULT 0;
  DECLARE v_message varchar(255);

  IF p_max_rows < 1 OR p_max_rows > 8388608 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'maxRows 必须在 1..8388608';
  END IF;
  IF p_worker_id < 0 OR p_worker_id > 255 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'workerId 必须在 0..255';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM (
    SELECT old_alarm_id FROM alarm_legacy_source_v
    GROUP BY old_alarm_id HAVING COUNT(*) > 1
  ) duplicated;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'alarm_0..4 存在重复 alarm_id';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM alarm_legacy_source_v
  WHERE route_alarm_beginTime IS NULL
     OR DATEDIFF(DATE(route_alarm_beginTime), '2020-01-01') NOT BETWEEN 0 AND 32767;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '存在无法编码日期的旧报警';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM (
    SELECT DATE_FORMAT(route_alarm_beginTime, '%Y%m') month_key
    FROM alarm_legacy_source_v
    GROUP BY DATE_FORMAT(route_alarm_beginTime, '%Y%m')
    HAVING COUNT(*) > p_max_rows * 100
  ) oversized;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '单月数据超过 100 个两位容量切片';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM (
    SELECT old_alarm_id FROM alarm_legacy_handle_source_v
    GROUP BY old_alarm_id HAVING COUNT(*) > 1
  ) duplicated_handle;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '旧处理表违反一警一处理记录约束';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM alarm_legacy_handle_source_v h
  LEFT JOIN alarm_legacy_source_v a
    ON a.source_table_no = h.source_table_no AND a.old_alarm_id = h.old_alarm_id
  WHERE a.old_alarm_id IS NULL;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '旧处理表存在孤儿 alarm_id';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM alarm_legacy_cell_source_v c
  LEFT JOIN alarm_legacy_source_v a
    ON a.source_table_no = c.source_table_no AND a.old_alarm_id = c.old_alarm_id
  WHERE a.old_alarm_id IS NULL;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '旧电解槽明细存在孤儿 alarm_id';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM (
    SELECT alarm_cid FROM alarm_legacy_source_v
    WHERE alarm_endTime IS NULL AND alarm_cid IS NOT NULL AND alarm_cid <> ''
    GROUP BY alarm_cid HAVING COUNT(*) > 1
  ) duplicated_cid;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '未关闭旧报警存在重复 alarm_cid';
  END IF;

  SELECT COUNT(*) INTO v_count
  FROM alarm_legacy_id_migration_map m
  LEFT JOIN alarm_legacy_source_v s ON s.old_alarm_id = m.old_alarm_id
  WHERE s.old_alarm_id IS NULL
     OR s.source_table_no <> m.source_table_no
     OR s.route_alarm_beginTime <> m.route_alarm_beginTime
     OR m.max_rows <> p_max_rows;
  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '已有映射与本次源数据或 maxRows 不一致';
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'alarm_stop_side_effect_event'
  ) THEN
    SET @legacy_check_sql =
      'SELECT COUNT(*) INTO @legacy_transient_count FROM alarm_stop_side_effect_event e JOIN alarm_legacy_source_v s ON s.old_alarm_id=e.alarm_id';
    PREPARE legacy_stmt FROM @legacy_check_sql;
    EXECUTE legacy_stmt;
    DEALLOCATE PREPARE legacy_stmt;
    IF @legacy_transient_count > 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '请先清空关联旧ID的 stop side effect 临时任务';
    END IF;
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'alarm_electrolytic_cell_snapshot_command'
  ) THEN
    SET @legacy_check_sql =
      'SELECT COUNT(*) INTO @legacy_transient_count FROM alarm_electrolytic_cell_snapshot_command e JOIN alarm_legacy_source_v s ON s.old_alarm_id=e.alarm_id';
    PREPARE legacy_stmt FROM @legacy_check_sql;
    EXECUTE legacy_stmt;
    DEALLOCATE PREPARE legacy_stmt;
    IF @legacy_transient_count > 0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '请先清空关联旧ID的电解槽快照临时命令';
    END IF;
  END IF;
END $$

DROP PROCEDURE IF EXISTS alarm_plan_legacy_id_mapping $$
CREATE PROCEDURE alarm_plan_legacy_id_mapping(IN p_max_rows bigint, IN p_worker_id int)
BEGIN
  DROP TEMPORARY TABLE IF EXISTS alarm_legacy_new_plan;
  CREATE TEMPORARY TABLE alarm_legacy_new_plan AS
  WITH existing_count AS (
    SELECT month_key, COUNT(*) mapped_rows
    FROM alarm_legacy_id_migration_map
    GROUP BY month_key
  ),
  ranked AS (
    SELECT s.*,
           COALESCE(e.mapped_rows, 0)
             + ROW_NUMBER() OVER (
                 PARTITION BY DATE_FORMAT(s.route_alarm_beginTime, '%Y%m')
                 ORDER BY s.source_table_no, s.old_alarm_id
               ) - 1 AS month_ordinal
    FROM alarm_legacy_source_v s
    LEFT JOIN alarm_legacy_id_migration_map m ON m.old_alarm_id = s.old_alarm_id
    LEFT JOIN existing_count e
      ON e.month_key = DATE_FORMAT(s.route_alarm_beginTime, '%Y%m')
    WHERE m.old_alarm_id IS NULL
  )
  SELECT old_alarm_id, source_table_no, source_alarm_beginTime, route_alarm_beginTime,
         DATE_FORMAT(route_alarm_beginTime, '%Y%m') month_key,
         FLOOR(month_ordinal / p_max_rows) slice_no,
         MOD(month_ordinal, p_max_rows) row_no
  FROM ranked;

  INSERT INTO alarm_legacy_id_migration_map (
    old_alarm_id, new_alarm_id, source_table_no, source_table_name,
    source_alarm_beginTime, route_alarm_beginTime, month_key, slice_no, row_no,
    table_suffix, max_rows, old_alarm_handle_id, migration_status, created_run_id
  )
  SELECT p.old_alarm_id,
         DATEDIFF(DATE(p.route_alarm_beginTime), '2020-01-01') * 281474976710656
           + p.slice_no * 1099511627776
           + p_worker_id * 4294967296
           + (p.old_alarm_id & 511) * 8388608
           + p.row_no,
         p.source_table_no,
         CONCAT('alarm_', p.source_table_no),
         p.source_alarm_beginTime,
         p.route_alarm_beginTime,
         p.month_key,
         p.slice_no,
         p.row_no,
         CONCAT(p.month_key, '_', LPAD(p.slice_no, 2, '0')),
         p_max_rows,
         h.old_alarm_handle_id,
         'PLANNED',
         @alarm_legacy_migration_run_id
  FROM alarm_legacy_new_plan p
  LEFT JOIN alarm_legacy_handle_source_v h
    ON h.source_table_no = p.source_table_no AND h.old_alarm_id = p.old_alarm_id;

  UPDATE alarm_legacy_id_migration_map m
  JOIN alarm_legacy_handle_source_v h
    ON h.source_table_no = m.source_table_no AND h.old_alarm_id = m.old_alarm_id
  SET m.old_alarm_handle_id = h.old_alarm_handle_id;
END $$

DROP PROCEDURE IF EXISTS alarm_copy_legacy_shards $$
CREATE PROCEDURE alarm_copy_legacy_shards(IN p_max_rows bigint)
BEGIN
  DECLARE v_done int DEFAULT 0;
  DECLARE v_suffix varchar(16);
  DECLARE v_target_alarm varchar(64);
  DECLARE v_target_handle varchar(64);
  DECLARE v_target_cell varchar(64);
  DECLARE v_message varchar(255);

  DECLARE suffix_cur CURSOR FOR
    SELECT DISTINCT table_suffix FROM alarm_legacy_id_migration_map ORDER BY table_suffix;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  OPEN suffix_cur;
  suffix_loop: LOOP
    FETCH suffix_cur INTO v_suffix;
    IF v_done = 1 THEN LEAVE suffix_loop; END IF;

    SET v_target_alarm = CONCAT('alarm_', v_suffix);
    SET v_target_handle = CONCAT('alarm_handle_', v_suffix);
    SET v_target_cell = CONCAT('alarm_electrolytic_cell_', v_suffix);

    SET @legacy_sql = CONCAT('CREATE TABLE IF NOT EXISTS `', v_target_alarm, '` LIKE alarm');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    SET @legacy_sql = CONCAT('CREATE TABLE IF NOT EXISTS `', v_target_handle, '` LIKE alarm_handle');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    SET @legacy_sql = CONCAT('CREATE TABLE IF NOT EXISTS `', v_target_cell, '` LIKE alarm_electrolytic_cell');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;

    IF NOT EXISTS (
      SELECT 1 FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND BINARY table_name = BINARY v_target_handle
        AND index_name = 'uk_alarm_handle_alarm_id'
    ) THEN
      SET @legacy_sql = CONCAT('ALTER TABLE `', v_target_handle,
        '` ADD UNIQUE KEY uk_alarm_handle_alarm_id (alarm_id)');
      PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    END IF;

    SET @legacy_sql = CONCAT(
      'SELECT COUNT(*) INTO @legacy_unexpected FROM `', v_target_alarm,
      '` t LEFT JOIN alarm_legacy_id_migration_map m ',
      'ON m.new_alarm_id=t.alarm_id AND m.table_suffix=''', v_suffix, ''' ',
      'WHERE m.old_alarm_id IS NULL');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    IF @legacy_unexpected > 0 THEN
      SET v_message = CONCAT(v_target_alarm, ' 已包含非本次迁移数据');
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = v_message;
    END IF;

    SET @legacy_sql = CONCAT(
      'INSERT INTO `', v_target_alarm, '` (alarm_id,device_sn,alarm_type,alarm_rank,alarm_status,',
      'scene_type,del_flag,alarm_beginTime,alarm_endTime,picture_path,video_picture,video_path,',
      'create_time,update_time,create_by,update_by,alarm_cid,irms_sn,area_sn,target_name,maxTemp,minTemp,tenant_id,remark_data) ',
      'SELECT m.new_alarm_id,s.device_sn,s.alarm_type,s.alarm_rank,s.alarm_status,s.scene_type,s.del_flag,',
      's.route_alarm_beginTime,s.alarm_endTime,s.picture_path,s.video_picture,s.video_path,s.create_time,',
      's.update_time,s.create_by,s.update_by,s.alarm_cid,s.irms_sn,s.area_sn,s.target_name,s.maxTemp,s.minTemp,s.tenant_id,s.remark_data ',
      'FROM alarm_legacy_source_v s JOIN alarm_legacy_id_migration_map m ON m.old_alarm_id=s.old_alarm_id ',
      'WHERE m.table_suffix=''', v_suffix, ''' ',
      'ON DUPLICATE KEY UPDATE device_sn=VALUES(device_sn),alarm_type=VALUES(alarm_type),alarm_rank=VALUES(alarm_rank),',
      'alarm_status=VALUES(alarm_status),scene_type=VALUES(scene_type),del_flag=VALUES(del_flag),',
      'alarm_beginTime=VALUES(alarm_beginTime),alarm_endTime=VALUES(alarm_endTime),picture_path=VALUES(picture_path),',
      'video_picture=VALUES(video_picture),video_path=VALUES(video_path),create_time=VALUES(create_time),',
      'update_time=VALUES(update_time),create_by=VALUES(create_by),update_by=VALUES(update_by),',
      'alarm_cid=VALUES(alarm_cid),irms_sn=VALUES(irms_sn),area_sn=VALUES(area_sn),target_name=VALUES(target_name),',
      'maxTemp=VALUES(maxTemp),minTemp=VALUES(minTemp),tenant_id=VALUES(tenant_id),remark_data=VALUES(remark_data)');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;

    SET @legacy_sql = CONCAT(
      'INSERT INTO `', v_target_handle, '` (alarm_id,workorder_id,handle_status,del_flag,identify,opinion,handler_id,',
      'handle_picture,create_time,create_by,update_time,update_by,handle_time,confirm_user_id,apparatus_id,handler_name,alarm_beginTime) ',
      'SELECT m.new_alarm_id,h.workorder_id,h.handle_status,h.del_flag,h.identify,h.opinion,h.handler_id,h.handle_picture,',
      'h.create_time,h.create_by,h.update_time,h.update_by,h.handle_time,h.confirm_user_id,h.apparatus_id,h.handler_name,m.route_alarm_beginTime ',
      'FROM alarm_legacy_handle_source_v h JOIN alarm_legacy_id_migration_map m ',
      'ON m.source_table_no=h.source_table_no AND m.old_alarm_id=h.old_alarm_id WHERE m.table_suffix=''', v_suffix, ''' ',
      'ON DUPLICATE KEY UPDATE workorder_id=VALUES(workorder_id),handle_status=VALUES(handle_status),del_flag=VALUES(del_flag),',
      'identify=VALUES(identify),opinion=VALUES(opinion),handler_id=VALUES(handler_id),handle_picture=VALUES(handle_picture),',
      'create_time=VALUES(create_time),create_by=VALUES(create_by),update_time=VALUES(update_time),update_by=VALUES(update_by),',
      'handle_time=VALUES(handle_time),confirm_user_id=VALUES(confirm_user_id),apparatus_id=VALUES(apparatus_id),',
      'handler_name=VALUES(handler_name),alarm_beginTime=VALUES(alarm_beginTime)');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;

    SET @legacy_sql = CONCAT(
      'UPDATE alarm_legacy_id_migration_map m JOIN `', v_target_handle,
      '` h ON h.alarm_id=m.new_alarm_id SET m.new_alarm_handle_id=h.alarm_handle_id ',
      'WHERE m.table_suffix=''', v_suffix, '''');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;

    SET @legacy_sql = CONCAT(
      'INSERT INTO `', v_target_cell, '` (alarm_id,sequence_id,row_index,groove_number,subdivide_number,',
      'observation_place,temperature_variation,repeat_number,repeat_time,repeat_handler_users,repeat_handle_time,alarm_beginTime,del_flag) ',
      'SELECT m.new_alarm_id,c.sequence_id,c.row_index,c.groove_number,c.subdivide_number,c.observation_place,',
      'c.temperature_variation,c.repeat_number,c.repeat_time,c.repeat_handler_users,c.repeat_handle_time,m.route_alarm_beginTime,c.del_flag ',
      'FROM alarm_legacy_cell_source_v c JOIN alarm_legacy_id_migration_map m ',
      'ON m.source_table_no=c.source_table_no AND m.old_alarm_id=c.old_alarm_id WHERE m.table_suffix=''', v_suffix, ''' ',
      'ON DUPLICATE KEY UPDATE sequence_id=VALUES(sequence_id),row_index=VALUES(row_index),groove_number=VALUES(groove_number),',
      'subdivide_number=VALUES(subdivide_number),observation_place=VALUES(observation_place),',
      'temperature_variation=VALUES(temperature_variation),repeat_number=VALUES(repeat_number),repeat_time=VALUES(repeat_time),',
      'repeat_handler_users=VALUES(repeat_handler_users),repeat_handle_time=VALUES(repeat_handle_time),',
      'alarm_beginTime=VALUES(alarm_beginTime),del_flag=VALUES(del_flag)');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;

    SET @legacy_sql = CONCAT(
      'INSERT INTO alarm_shard_slice (month_key,slice_no,table_suffix,current_rows,max_rows,status) ',
      'SELECT LEFT(''', v_suffix, ''',6),CAST(SUBSTRING(''', v_suffix, ''',8) AS UNSIGNED),''', v_suffix,
      ''',COUNT(*),', p_max_rows, ',IF(COUNT(*)>=', p_max_rows, ',''FULL'',''ACTIVE'') FROM `', v_target_alarm, '` ',
      'ON DUPLICATE KEY UPDATE current_rows=VALUES(current_rows),max_rows=VALUES(max_rows),status=VALUES(status)');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;

    UPDATE alarm_legacy_id_migration_map
    SET migration_status = 'COPIED'
    WHERE BINARY table_suffix = BINARY v_suffix;
  END LOOP;
  CLOSE suffix_cur;
END $$

DROP PROCEDURE IF EXISTS alarm_migrate_legacy_unsharded_relations $$
CREATE PROCEDURE alarm_migrate_legacy_unsharded_relations()
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='alarm_partial_discharge'
  ) THEN
    INSERT INTO alarm_partial_discharge (
      alarm_id,sensor_type,channel_index,sensor_id,cycle_unit,alarm_frequency,
      attention_number,alarm_number,max_amplitude,pd_type,del_flag
    )
    SELECT m.new_alarm_id,p.sensor_type,p.channel_index,p.sensor_id,p.cycle_unit,p.alarm_frequency,
           p.attention_number,p.alarm_number,p.max_amplitude,p.pd_type,p.del_flag
    FROM alarm_partial_discharge p
    JOIN alarm_legacy_id_migration_map m ON m.old_alarm_id=p.alarm_id
    ON DUPLICATE KEY UPDATE sensor_type=VALUES(sensor_type),channel_index=VALUES(channel_index),
      sensor_id=VALUES(sensor_id),cycle_unit=VALUES(cycle_unit),alarm_frequency=VALUES(alarm_frequency),
      attention_number=VALUES(attention_number),alarm_number=VALUES(alarm_number),
      max_amplitude=VALUES(max_amplitude),pd_type=VALUES(pd_type),del_flag=VALUES(del_flag);
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='alarm_electrolytic_cell_ectype'
  ) THEN
    CREATE TABLE IF NOT EXISTS alarm_legacy_backup_alarm_electrolytic_cell_ectype
      LIKE alarm_electrolytic_cell_ectype;
    INSERT IGNORE INTO alarm_legacy_backup_alarm_electrolytic_cell_ectype
      SELECT * FROM alarm_electrolytic_cell_ectype;
    UPDATE alarm_electrolytic_cell_ectype e
    JOIN alarm_legacy_id_migration_map m ON m.old_alarm_id=e.alarm_id
    SET e.alarm_id=m.new_alarm_id;
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='alarm_workorder'
  ) THEN
    CREATE TABLE IF NOT EXISTS alarm_legacy_backup_alarm_workorder LIKE alarm_workorder;
    INSERT IGNORE INTO alarm_legacy_backup_alarm_workorder SELECT * FROM alarm_workorder;
    UPDATE alarm_workorder w
    JOIN alarm_legacy_id_migration_map m ON m.old_alarm_id=w.alarm_id
    SET w.alarm_id=m.new_alarm_id;
  END IF;
END $$

DROP PROCEDURE IF EXISTS alarm_rebuild_legacy_active_routes $$
CREATE PROCEDURE alarm_rebuild_legacy_active_routes(IN p_hot_hours int, IN p_stale_expire_days int)
BEGIN
  DELETE r FROM alarm_cid_index r
  JOIN alarm_legacy_id_migration_map m
    ON r.alarm_id IN (m.old_alarm_id, m.new_alarm_id);
  DELETE r FROM alarm_cid_stale_index r
  JOIN alarm_legacy_id_migration_map m
    ON r.alarm_id IN (m.old_alarm_id, m.new_alarm_id);

  INSERT INTO alarm_cid_index (
    alarm_cid,alarm_id,alarm_beginTime,alarm_endTime,table_suffix,device_sn,irms_sn,
    alarm_type,route_status,delete_after
  )
  SELECT s.alarm_cid,m.new_alarm_id,m.route_alarm_beginTime,s.alarm_endTime,m.table_suffix,
         s.device_sn,s.irms_sn,s.alarm_type,'ACTIVE',NULL
  FROM alarm_legacy_source_v s
  JOIN alarm_legacy_id_migration_map m ON m.old_alarm_id=s.old_alarm_id
  WHERE s.alarm_cid IS NOT NULL AND s.alarm_cid <> ''
    AND s.alarm_endTime IS NULL
    AND m.route_alarm_beginTime >= DATE_SUB(NOW(), INTERVAL p_hot_hours HOUR)
  ON DUPLICATE KEY UPDATE alarm_id=VALUES(alarm_id),alarm_beginTime=VALUES(alarm_beginTime),
    alarm_endTime=VALUES(alarm_endTime),table_suffix=VALUES(table_suffix),device_sn=VALUES(device_sn),
    irms_sn=VALUES(irms_sn),alarm_type=VALUES(alarm_type),route_status='ACTIVE',delete_after=NULL;

  INSERT INTO alarm_cid_stale_index (
    alarm_cid,alarm_id,alarm_beginTime,alarm_endTime,table_suffix,device_sn,irms_sn,
    alarm_type,route_status,stale_time,expire_time,delete_after
  )
  SELECT s.alarm_cid,m.new_alarm_id,m.route_alarm_beginTime,s.alarm_endTime,m.table_suffix,
         s.device_sn,s.irms_sn,s.alarm_type,'ACTIVE',NOW(),
         DATE_ADD(NOW(), INTERVAL p_stale_expire_days DAY),NULL
  FROM alarm_legacy_source_v s
  JOIN alarm_legacy_id_migration_map m ON m.old_alarm_id=s.old_alarm_id
  WHERE s.alarm_cid IS NOT NULL AND s.alarm_cid <> ''
    AND s.alarm_endTime IS NULL
    AND m.route_alarm_beginTime < DATE_SUB(NOW(), INTERVAL p_hot_hours HOUR)
  ON DUPLICATE KEY UPDATE alarm_id=VALUES(alarm_id),alarm_beginTime=VALUES(alarm_beginTime),
    alarm_endTime=VALUES(alarm_endTime),table_suffix=VALUES(table_suffix),device_sn=VALUES(device_sn),
    irms_sn=VALUES(irms_sn),alarm_type=VALUES(alarm_type),route_status='ACTIVE',
    stale_time=VALUES(stale_time),expire_time=VALUES(expire_time),delete_after=NULL;
END $$

DROP PROCEDURE IF EXISTS alarm_verify_legacy_id_rewrite $$
CREATE PROCEDURE alarm_verify_legacy_id_rewrite()
BEGIN
  DECLARE v_done int DEFAULT 0;
  DECLARE v_suffix varchar(16);
  DECLARE v_expected bigint;
  DECLARE v_actual bigint;
  DECLARE v_mismatch bigint;
  DECLARE v_failed bigint DEFAULT 0;
  DECLARE v_target varchar(64);
  DECLARE v_entity varchar(32);

  DECLARE suffix_cur CURSOR FOR
    SELECT DISTINCT table_suffix FROM alarm_legacy_id_migration_map ORDER BY table_suffix;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

  DELETE FROM alarm_legacy_migration_audit WHERE run_id=@alarm_legacy_migration_run_id;

  OPEN suffix_cur;
  verify_loop: LOOP
    FETCH suffix_cur INTO v_suffix;
    IF v_done = 1 THEN LEAVE verify_loop; END IF;

    SELECT COUNT(*) INTO v_expected
    FROM alarm_legacy_id_migration_map WHERE BINARY table_suffix=BINARY v_suffix;
    SET v_target=CONCAT('alarm_',v_suffix);
    SET @legacy_sql=CONCAT('SELECT COUNT(*) INTO @legacy_actual FROM `',v_target,'`');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    SET v_actual=@legacy_actual;
    SET @legacy_sql=CONCAT(
      'SELECT COUNT(*) INTO @legacy_mismatch FROM `',v_target,'` t ',
      'JOIN alarm_legacy_id_migration_map m ON m.new_alarm_id=t.alarm_id ',
      'JOIN alarm_legacy_source_v s ON s.old_alarm_id=m.old_alarm_id ',
      'WHERE m.table_suffix=''',v_suffix,''' AND (',
      'NOT(t.device_sn<=>s.device_sn) OR NOT(t.alarm_type<=>s.alarm_type) OR ',
      'NOT(t.alarm_rank<=>s.alarm_rank) OR NOT(t.alarm_status<=>s.alarm_status) OR ',
      'NOT(t.alarm_beginTime<=>m.route_alarm_beginTime) OR NOT(t.alarm_endTime<=>s.alarm_endTime) OR ',
      'NOT(t.alarm_cid<=>s.alarm_cid) OR NOT(t.tenant_id<=>s.tenant_id) OR NOT(t.remark_data<=>s.remark_data))');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    SET v_mismatch=@legacy_mismatch;
    INSERT INTO alarm_legacy_migration_audit
      (run_id,table_suffix,entity_name,expected_rows,actual_rows,mismatch_rows,audit_status)
    VALUES (@alarm_legacy_migration_run_id,v_suffix,'alarm',v_expected,v_actual,v_mismatch,
            IF(v_expected=v_actual AND v_mismatch=0,'PASS','FAIL'));

    SELECT COUNT(*) INTO v_expected
    FROM alarm_legacy_handle_source_v h
    JOIN alarm_legacy_id_migration_map m
      ON m.source_table_no=h.source_table_no AND m.old_alarm_id=h.old_alarm_id
    WHERE BINARY m.table_suffix=BINARY v_suffix;
    SET v_target=CONCAT('alarm_handle_',v_suffix);
    SET @legacy_sql=CONCAT('SELECT COUNT(*) INTO @legacy_actual FROM `',v_target,'`');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    SET v_actual=@legacy_actual;
    INSERT INTO alarm_legacy_migration_audit
      (run_id,table_suffix,entity_name,expected_rows,actual_rows,mismatch_rows,audit_status)
    VALUES (@alarm_legacy_migration_run_id,v_suffix,'alarm_handle',v_expected,v_actual,0,
            IF(v_expected=v_actual,'PASS','FAIL'));

    SELECT COUNT(*) INTO v_expected
    FROM alarm_legacy_cell_source_v c
    JOIN alarm_legacy_id_migration_map m
      ON m.source_table_no=c.source_table_no AND m.old_alarm_id=c.old_alarm_id
    WHERE BINARY m.table_suffix=BINARY v_suffix;
    SET v_target=CONCAT('alarm_electrolytic_cell_',v_suffix);
    SET @legacy_sql=CONCAT('SELECT COUNT(*) INTO @legacy_actual FROM `',v_target,'`');
    PREPARE legacy_stmt FROM @legacy_sql; EXECUTE legacy_stmt; DEALLOCATE PREPARE legacy_stmt;
    SET v_actual=@legacy_actual;
    INSERT INTO alarm_legacy_migration_audit
      (run_id,table_suffix,entity_name,expected_rows,actual_rows,mismatch_rows,audit_status)
    VALUES (@alarm_legacy_migration_run_id,v_suffix,'alarm_electrolytic_cell',v_expected,v_actual,0,
            IF(v_expected=v_actual,'PASS','FAIL'));
  END LOOP;
  CLOSE suffix_cur;

  SELECT COUNT(*) INTO v_mismatch
  FROM alarm_legacy_id_migration_map
  WHERE migration_status <> 'COPIED'
     OR FLOOR(new_alarm_id / 281474976710656) <> DATEDIFF(DATE(route_alarm_beginTime),'2020-01-01')
     OR FLOOR(new_alarm_id / 1099511627776) MOD 256 <> slice_no
     OR new_alarm_id MOD 8388608 <> row_no;
  IF v_mismatch > 0 THEN
    INSERT INTO alarm_legacy_migration_audit
      (run_id,table_suffix,entity_name,expected_rows,actual_rows,mismatch_rows,audit_status)
    VALUES (@alarm_legacy_migration_run_id,'ALL','id_codec',
            (SELECT COUNT(*) FROM alarm_legacy_id_migration_map),
            (SELECT COUNT(*) FROM alarm_legacy_id_migration_map)-v_mismatch,
            v_mismatch,'FAIL');
  END IF;

  SELECT COUNT(*) INTO v_failed
  FROM alarm_legacy_migration_audit
  WHERE run_id=@alarm_legacy_migration_run_id AND audit_status='FAIL';
  IF v_failed > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='迁移校验失败，请查看 alarm_legacy_migration_audit';
  END IF;
END $$

DROP PROCEDURE IF EXISTS alarm_run_legacy_0_4_id_rewrite_migration $$
CREATE PROCEDURE alarm_run_legacy_0_4_id_rewrite_migration(
  IN p_max_rows bigint,
  IN p_worker_id int,
  IN p_hot_hours int,
  IN p_stale_expire_days int
)
BEGIN
  DECLARE v_error varchar(1024);
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    GET DIAGNOSTICS CONDITION 1 v_error = MESSAGE_TEXT;
    UPDATE alarm_legacy_migration_run
    SET status='FAILED',error_message=v_error,finished_time=NOW()
    WHERE run_id=@alarm_legacy_migration_run_id;
    RESIGNAL;
  END;

  IF p_hot_hours < 1 OR p_stale_expire_days < 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='hotHours 和 staleExpireDays 必须大于 0';
  END IF;

  SET @alarm_legacy_migration_run_id=UUID();
  INSERT INTO alarm_legacy_migration_run
    (run_id,max_rows,worker_id,hot_hours,stale_expire_days,status)
  VALUES
    (@alarm_legacy_migration_run_id,p_max_rows,p_worker_id,p_hot_hours,p_stale_expire_days,'RUNNING');

  CALL alarm_assert_legacy_id_rewrite_ready(p_max_rows,p_worker_id);
  CALL alarm_plan_legacy_id_mapping(p_max_rows,p_worker_id);
  CALL alarm_copy_legacy_shards(p_max_rows);
  CALL alarm_migrate_legacy_unsharded_relations();
  CALL alarm_rebuild_legacy_active_routes(p_hot_hours,p_stale_expire_days);
  CALL alarm_verify_legacy_id_rewrite();

  UPDATE alarm_legacy_migration_run
  SET source_rows=(SELECT COUNT(*) FROM alarm_legacy_source_v),
      mapped_rows=(SELECT COUNT(*) FROM alarm_legacy_id_migration_map),
      status='PASS',finished_time=NOW()
  WHERE run_id=@alarm_legacy_migration_run_id;

  SELECT @alarm_legacy_migration_run_id AS run_id,
         (SELECT COUNT(*) FROM alarm_legacy_source_v) AS source_rows,
         (SELECT COUNT(*) FROM alarm_legacy_id_migration_map) AS mapped_rows,
         'PASS' AS migration_status;
END $$

DELIMITER ;

-- 仅预检：
-- CALL alarm_assert_legacy_id_rewrite_ready(5000000, 0);
--
-- 正式执行（必须停写）：
-- CALL alarm_run_legacy_0_4_id_rewrite_migration(5000000, 0, 24, 30);
--
-- CSV 导出数据源：
-- SELECT old_alarm_id,new_alarm_id,source_table_name,old_alarm_handle_id,new_alarm_handle_id,
--        source_alarm_beginTime,route_alarm_beginTime,month_key,slice_no,row_no,table_suffix,
--        migration_status,created_run_id,created_time
-- FROM alarm_legacy_id_migration_map
-- ORDER BY route_alarm_beginTime,source_table_no,old_alarm_id;
