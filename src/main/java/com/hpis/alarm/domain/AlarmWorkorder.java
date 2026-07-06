package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;

/**
 * 报警工单对象 alarm_workorder。
 */
@Data
public class AlarmWorkorder extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 工单ID */
    @TableId(type = IdType.AUTO)
    private Long workorderId;

    /** 工单编号 */
    private String workorderNo;

    /** 报警ID */
    private Long alarmId;

    /** 来源工单模板ID */
    private Long workorderConfigId;

    /** 工单状态：0待处理 1处理中 2已完成 3已关闭 4退回 */
    private String status;

    /** 当前负责人ID */
    private Long assigneeId;

    /** 当前负责人名称 */
    private String assigneeName;

    /** 工单标题 */
    private String title;

    /** 工单内容 */
    private String content;

    /** 工单处理结果 */
    private String handleResult;

    /** 租户ID */
    private Long tenantId;

    /** 逻辑删除：0存在 2删除 */
    private String delFlag;
}
