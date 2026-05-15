package com.hpis.alarm.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.common.core.annotation.Excel;
import lombok.Data;
import org.springframework.data.annotation.Transient;

import java.util.Date;
import java.util.List;

/**
 * @author 向
 */
@Data
public class HandleParamDto {
    /** 报警处理id */
    private Long alarmHandleId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    /** 唯一警报id */
    @Excel(name = "唯一警报id")
    private Long alarmId;

    /** 报警处理人id */
    @Excel(name = "报警处理人id")
    private Long handlerId;


    private String[]  alarmTypes;


    /** 序列唯一标识 */
    private String sequenceId;

    /** 跨号 */
    private Integer rowIndex;

    /** 槽号 */
    private Integer grooveNumber;

    /** 细分号（导电条号或极板号） */
    private Integer subdivideNumber;

    /** 观察位置 */
    @Excel(name = "观察位置")
    private String observationPlace;

    /** 设备唯一id */
    @Excel(name = "设备唯一id")
    private Long deviceId;

    /** 警报类型 */
    @Excel(name = "警报类型")
    private String alarmType;

    /** 警报级别 */
    @Excel(name = "警报级别")
    private String alarmRank;


    /** 警报状态（0代表未处理 1代表处理） */
    @Excel(name = "警报状态", readConverterExp = "0=代表未处理,1=代表处理")
    private String alarmStatus;

    /** 处理状态-1等待处理，0未处理 1已处理 */
    @Excel(name = "处理状态-1等待处理，0未处理 1已处理")
    private String handleStatus;


    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Excel(name = "报警开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date alarmBegintime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Excel(name = "报警结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date alarmEndtime;

    /** 处理时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date handleTime;

    /** 逻辑删除0存在，2删除 */
    private String delFlag;

    /** 0为真实 1为误报 */
    @Excel(name = "0为真实 1为误报")
    private String identify;

    /** 处理意见 */
    @Excel(name = "处理意见")
    private String opinion;

    private String irmsSn;

    private String areaSn;

    /**观察位置**/
    @Transient
    private  String targetName;

    /**
     * 设备名称
     */
    @Transient
    private String deviceName;

    /**
     * 未停止报警标识0
     */
    @Transient
    private Integer stopAlarmFlag;

    /**
     * 客户id
     */
    @Transient
    private String customerId;

    /**行业id**/
    private String sceneType;

    /**处理 图片 **/
    private String handlePicture;

    /**确认人id **/
    private Long confirmUserId;
    /**巡检仪标识 **/
    private String apparatusId;

    private String handlerName;

    private Long[] alarmIds;

    private List<EcHandleSave> ecHandles;
}
