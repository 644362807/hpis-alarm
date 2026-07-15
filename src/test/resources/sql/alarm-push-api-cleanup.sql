-- Alarm Push API 运行数据清理（MySQL 8.x）
-- 配置清理必须先通过 API 完成：
--   DELETE /pushConfig/{ids}
--   DELETE /configure/delete/{ids}
-- 本脚本不删除 alarm_configure、alarm_device_configure、active_push_config、pushconfigid_devicesn。

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS hpis_alarm.cleanup_api_push_e2e_shards;
DELIMITER $$
CREATE PROCEDURE hpis_alarm.cleanup_api_push_e2e_shards()
BEGIN
    DECLARE done INT DEFAULT 0;
    DECLARE suffix_value VARCHAR(16);
    DECLARE suffix_cursor CURSOR FOR
        SELECT DISTINCT table_suffix
        FROM hpis_alarm.alarm_cid_index
        WHERE alarm_cid LIKE 'API-PUSH-E2E-%'
          AND table_suffix REGEXP '^[0-9]{6}_[0-9]{2}$';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = 1;

    OPEN suffix_cursor;
    cleanup_loop: LOOP
        FETCH suffix_cursor INTO suffix_value;
        IF done = 1 THEN
            LEAVE cleanup_loop;
        END IF;

        SET @delete_handle_sql = CONCAT(
            'DELETE h FROM hpis_alarm.alarm_handle_', suffix_value,
            ' h JOIN hpis_alarm.alarm_cid_index i ON i.alarm_id=h.alarm_id ',
            'WHERE i.alarm_cid LIKE ''API-PUSH-E2E-%'''
        );
        PREPARE delete_handle_stmt FROM @delete_handle_sql;
        EXECUTE delete_handle_stmt;
        DEALLOCATE PREPARE delete_handle_stmt;

        SET @delete_alarm_sql = CONCAT(
            'DELETE a FROM hpis_alarm.alarm_', suffix_value,
            ' a JOIN hpis_alarm.alarm_cid_index i ON i.alarm_id=a.alarm_id ',
            'WHERE i.alarm_cid LIKE ''API-PUSH-E2E-%'''
        );
        PREPARE delete_alarm_stmt FROM @delete_alarm_sql;
        EXECUTE delete_alarm_stmt;
        DEALLOCATE PREPARE delete_alarm_stmt;
    END LOOP;
    CLOSE suffix_cursor;
END$$
DELIMITER ;

DELETE w
FROM hpis_alarm.alarm_workorder w
JOIN hpis_alarm.alarm_cid_index i ON i.alarm_id = w.alarm_id
WHERE i.alarm_cid LIKE 'API-PUSH-E2E-%';

CALL hpis_alarm.cleanup_api_push_e2e_shards();
DROP PROCEDURE hpis_alarm.cleanup_api_push_e2e_shards;

DELETE FROM hpis_alarm.alarm_cid_index
WHERE alarm_cid LIKE 'API-PUSH-E2E-%';

DELETE FROM hpis_push.push_message_log
WHERE target_name LIKE 'api-push-e2e-%'
   OR message_data LIKE '%API-PUSH-E2E-%';

SELECT 'alarm_index' AS item, COUNT(*) AS remaining
FROM hpis_alarm.alarm_cid_index
WHERE alarm_cid LIKE 'API-PUSH-E2E-%'
UNION ALL
SELECT 'push_log', COUNT(*)
FROM hpis_push.push_message_log
WHERE target_name LIKE 'api-push-e2e-%'
   OR message_data LIKE '%API-PUSH-E2E-%'
UNION ALL
SELECT 'alarm_config_should_be_deleted_by_api', COUNT(*)
FROM hpis_alarm.alarm_configure
WHERE alarm_configure_name LIKE 'api-push-e2e-%'
UNION ALL
SELECT 'push_config_should_be_deleted_by_api', COUNT(*)
FROM hpis_push.active_push_config
WHERE config_name LIKE 'api-push-e2e-%';
