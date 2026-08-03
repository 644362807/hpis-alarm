package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;

import java.util.Date;

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

    /** 督促推送目标ID：NULL不推送、0接收组、正数定向用户 */
    private Long assigneeId;

    /** 定向督促目标名称；非正数模式为空 */
    private String assigneeName;

    /** 工单标题 */
    private String title;

    /** 工单内容 */
    private String content;

    /** 工单处理结果 */
    private String handleResult;

    /** 处理图片，实际存储于 alarm_handle.handle_picture */
    @TableField(exist = false)
    private String handlePicture;

    /** 推送目标模式：NONE不推送、GROUP接收组、DIRECT定向用户 */
    @TableField(exist = false)
    private String pushTargetMode;

    /** 关联报警状态 */
    @TableField(exist = false)
    private String alarmStatus;

    /** 关联报警结束时间 */
    @TableField(exist = false)
    private Date alarmEndtime;

    /** 关联报警处理状态 */
    @TableField(exist = false)
    private String handleStatus;

    /** 实际处理人ID */
    @TableField(exist = false)
    private Long handlerId;

    /** 实际处理人名称 */
    @TableField(exist = false)
    private String handlerName;

    /** 当前是否允许从督促记录进入报警处理 */
    @TableField(exist = false)
    private Boolean processable;

    /** 不可处理原因码 */
    @TableField(exist = false)
    private String unprocessableReason;

    /** 租户ID */
    private Long tenantId;

    /** 逻辑删除：0存在 2删除 */
    private String delFlag;
}
