package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * 声音报警器管理对象 sound_alarm
 *
 * @author pc
 * @date 2023-08-18
 */
@Data
public class SoundAlarm extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** $column.columnComment */
    @Excel(name = "${comment}", readConverterExp = "$column.readConverterExp()")
    @TableId(type = IdType.AUTO)
    private Long soundId;

    /** 客户名称 */
    @Excel(name = "客户名称")
    private Long customerId;

//    /** 报警器名称 */
//    @Excel(name = "报警器名称")
//    private String soundName;

    /** 报警器名称 */
    @Excel(name = "报警器名称")
    private String soundLocation;

    /** 排列序号 */
    @Excel(name = "排列序号")
    private Integer soundOrder;

    /** $column.columnComment */
    private String delFlag;

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("soundId", getSoundId())
            .append("customerId", getCustomerId())
//            .append("soundName", getSoundName())
            .append("soundName", getSoundLocation())
            .append("soundOrder", getSoundOrder())
            .append("updateTime", getUpdateTime())
            .append("updateBy", getUpdateBy())
            .append("createTime", getCreateTime())
            .append("createBy", getCreateBy())
            .append("delFlag", getDelFlag())
            .toString();
    }
}
