package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.util.Date;

/**
 * 推送记录对象 alarm_send_log
 *
 * @author pc
 * @date 2023-08-09
 */
@Data
public class AlarmSendLog extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 记录id */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    @TableId(type = IdType.AUTO)
    private Long sendLogId;

    /** 客户id */
    @Excel(name = "客户id")
    private Long customerId;

    /** 发送状态 1-成功 0-失败 */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String sendStatus;

    /** 发送方式 email,sms... */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String sendMethod;

    /** 发送目标 */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private String sendTarget;

    /** 发送内容 */
    private String sendContent;

    /** 发送时间 */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    private Date sendTime;

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("sendLogId", getSendLogId())
            .append("customerId", getCustomerId())
            .append("sendStatus", getSendStatus())
            .append("sendMethod", getSendMethod())
            .append("sendTarget", getSendTarget())
            .append("sendContent", getSendContent())
            .append("sendTime", getSendTime())
            .append("createTime", getCreateTime())
            .append("createBy", getCreateBy())
            .append("updateTime", getUpdateTime())
            .append("updateBy", getUpdateBy())
            .toString();
    }
}
