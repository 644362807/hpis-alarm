-- 报警扩展表逻辑删除列迁移。
-- 可重复执行：仅修改当前库中已存在且缺少 del_flag 的目标表。

DROP PROCEDURE IF EXISTS add_alarm_extension_del_flag;

DELIMITER $$

CREATE PROCEDURE add_alarm_extension_del_flag()
BEGIN
    DECLARE finished INTEGER DEFAULT 0;
    DECLARE target_table VARCHAR(64);
    DECLARE target_tables CURSOR FOR
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND (
              table_name IN ('alarm_electrolytic_cell', 'alarm_partial_discharge')
              OR table_name REGEXP '^alarm_electrolytic_cell_[0-9]{6}_[0-9]{2}$'
          );
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = 1;

    OPEN target_tables;

    migrate_table: LOOP
        FETCH target_tables INTO target_table;
        IF finished = 1 THEN
            LEAVE migrate_table;
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = target_table
              AND column_name = 'del_flag'
        ) THEN
            SET @ddl = CONCAT(
                'ALTER TABLE `', target_table,
                '` ADD COLUMN del_flag char(2) NOT NULL DEFAULT ''0'' ',
                'COMMENT ''删除标志（0代表存在 2代表删除）'''
            );
            PREPARE ddl_statement FROM @ddl;
            EXECUTE ddl_statement;
            DEALLOCATE PREPARE ddl_statement;
        END IF;
    END LOOP;

    CLOSE target_tables;
END$$

DELIMITER ;

CALL add_alarm_extension_del_flag();
DROP PROCEDURE add_alarm_extension_del_flag;
