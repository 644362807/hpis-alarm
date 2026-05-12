package com.hpis.alarm.config.sharding;

/**
 * 单次报警写入的分片上下文。
 *
 * <p>ShardingSphere 4.1.x 的自定义分片算法只能看到 SQL 中的分片键，不能天然知道
 * “主表、处理表、电解槽表必须落到同一个月内子表”。因此写入 alarm 前先由路由服务
 * 计算 table_suffix，再放入 ThreadLocal。后续同一个线程里的 alarm、alarm_handle、
 * alarm_electrolytic_cell 三次插入都会优先使用该 suffix，从而保证绑定表 join 不跨片。</p>
 */
public final class AlarmShardContext {

    private static final ThreadLocal<String> TABLE_SUFFIX = new ThreadLocal<>();

    private AlarmShardContext() {
    }

    public static void setTableSuffix(String tableSuffix) {
        TABLE_SUFFIX.set(tableSuffix);
    }

    public static String getTableSuffix() {
        return TABLE_SUFFIX.get();
    }

    public static void clear() {
        TABLE_SUFFIX.remove();
    }
}
