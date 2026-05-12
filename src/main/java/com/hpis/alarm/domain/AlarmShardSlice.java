package com.hpis.alarm.domain;

import lombok.Data;

/**
 * 月内容量切片元数据。
 *
 * <p>month_key 表示 yyyyMM，slice_no 表示月内序号，table_suffix 对应
 * yyyyMM_nn。current_rows 是写入路由时的容量计数，用于判断是否需要创建
 * 下一个月内子表。</p>
 */
@Data
public class AlarmShardSlice {

    private String monthKey;

    private Integer sliceNo;

    private String tableSuffix;

    private Long currentRows;

    private Long maxRows;
}
