-- Push消息目录/等级过滤发布前只读检查
SET NAMES utf8mb4;

SELECT table_name
FROM information_schema.tables
WHERE table_schema='hpis_push'
  AND table_name IN ('active_push_config','push_message_type_catalog','push_message_log',
                     'push_wecom_app_config','push_wecom_user_binding',
                     'push_recipient_group','push_recipient_group_member')
ORDER BY table_name;

SELECT table_name,column_name,column_type,is_nullable,column_default
FROM information_schema.columns
WHERE table_schema='hpis_push'
  AND ((table_name='active_push_config' AND column_name IN
       ('message_type','excluded_dict_values','route_scope','recipient_group_id'))
    OR (table_name='push_message_type_catalog' AND column_name IN
       ('message_group','message_type','message_type_name','dict_filter_supported',
        'filter_dict_type','enabled','del_flag')))
ORDER BY table_name,ordinal_position;

SELECT DISTINCT c.message_type AS missing_catalog_message_type
FROM hpis_push.active_push_config c
LEFT JOIN hpis_push.push_message_type_catalog t
  ON t.message_type=c.message_type AND (t.del_flag='0' OR t.del_flag IS NULL)
WHERE c.message_type IS NOT NULL AND c.message_type<>'' AND t.id IS NULL
ORDER BY c.message_type;

SELECT id,message_group,message_type,message_type_name,dict_filter_supported,
       filter_dict_type,enabled,del_flag,sort_no
FROM hpis_push.push_message_type_catalog
ORDER BY sort_no,id;

SELECT message_type,excluded_dict_values,COUNT(*) config_count
FROM hpis_push.active_push_config
WHERE del_flag='0' OR del_flag IS NULL
GROUP BY message_type,excluded_dict_values
ORDER BY message_type,excluded_dict_values;

-- 以下问题计数在开启过滤前必须为0。
-- 目录表可存储30字符，但当前active_push_config.message_type仍是CHAR(5)。
SELECT 'catalog_message_type_over_active_limit' item,COUNT(*) problem_count
FROM hpis_push.push_message_type_catalog
WHERE CHAR_LENGTH(message_type)>5
  AND (del_flag='0' OR del_flag IS NULL)
UNION ALL
SELECT 'invalid_catalog_filter_metadata',COUNT(*)
FROM hpis_push.push_message_type_catalog
WHERE (del_flag='0' OR del_flag IS NULL)
  AND ((dict_filter_supported=1 AND COALESCE(filter_dict_type,'')<>'alarm_rank')
    OR (dict_filter_supported=0 AND filter_dict_type IS NOT NULL AND filter_dict_type<>''))
UNION ALL
SELECT 'invalid_alarm_rank_excluded_values',COUNT(*)
FROM hpis_push.active_push_config c
JOIN hpis_push.push_message_type_catalog t ON t.message_type=c.message_type
WHERE (c.del_flag='0' OR c.del_flag IS NULL)
  AND (t.del_flag='0' OR t.del_flag IS NULL)
  AND t.dict_filter_supported=1
  AND t.filter_dict_type='alarm_rank'
  AND COALESCE(c.excluded_dict_values,'')<>''
  AND TRIM(c.excluded_dict_values) NOT REGEXP '^(1|2|3)([[:space:]]*,[[:space:]]*(1|2|3))*$';

-- System字典不由Push数据库SQL维护。继续通过接口验证：
-- GET /dict/data/type/push_message_group
-- GET /dict/data/type/alarm_rank
-- Redis只读验证：sys_dict2:push_message_group、sys_dict2:alarm_rank
