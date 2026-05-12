package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.hpis.common.core.annotation.Excel;


import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

/**
 * 报警颜色显示对象 alarm_color
 * 
 * @author ds
 * @date 2024-07-03
 */
@Data
public class AlarmColor
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long colorId;

    /** irmsSn */
    @Excel(name = "irmsSn")
    private String irmsSn;

    /** 正常 */
    private Integer color0;

    /** 一般报警 */
    private Integer color1;

    /** 紧急报警 */
    private Integer color2;

    /** 严重报警 */
    private Integer color3;

    /** 底色 */
    private Integer bottomColor;

    /** 入液出液颜色 */
    private Integer flowColor;

    /** 导电条颜色 */
    private Integer busBarColor;

    /** 电极板颜色 */
    private Integer electrodesColor;

    /** 电压颜色 */
    private Integer voltageColor;

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("colorId", getColorId())
            .append("irmsSn", getIrmsSn())
            .append("color0", getColor0())
            .append("color1", getColor1())
            .append("color2", getColor2())
            .append("color3", getColor3())
            .toString();
    }
}
