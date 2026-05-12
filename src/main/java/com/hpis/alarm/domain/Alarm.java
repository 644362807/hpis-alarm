package com.hpis.alarm.domain;


import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.alarm.dto.AlarmVideo;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

/**
 * 【请填写功能名称】对象 alarm
 * 
 * @author ruoyi
 * @date 2023-03-21
 */
@Data
public class Alarm extends BaseEntity
{

    public static final String DICT_ALARM_TYPE = "alarm_type";

    public static final String DICT_ALARM_RANK = "alarm_rank";
    private static final long serialVersionUID = 1L;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    /** 唯一警报号id */
    @TableId(type = IdType.INPUT)
    private Long alarmId;

    /** 设备唯一id */
    @Excel(name = "设备唯一id")
    @TableField(exist = false)
    private Long deviceId;

    /** 设备sn*/
    private String deviceSn;

    /** 警报类型 */
    @Excel(name = "警报类型")
    private String alarmType;

    /** 警报级别 */
    @Excel(name = "警报级别")
    private String alarmRank;

    /** 警报时间 （App查询字段） */
    @TableField(exist = false)
    private String alarmTime;

    /** 警报状态（0代表未处理 1代表处理） */
    @Excel(name = "警报状态", readConverterExp = "0=代表未处理,1=代表处理")
    private String alarmStatus;

    /** 删除标志（0代表存在 2代表删除） */
    private String delFlag;

    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "报警开始时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date alarmBegintime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Excel(name = "报警结束时间", width = 30, dateFormat = "yyyy-MM-dd")
    private Date alarmEndtime;

    /** 报警图片地址 */
    @Excel(name = "报警图片地址")
    private String picturePath;

    /** 报警视频地址 */
    @Excel(name = "报警视频地址")
    private String videoPath;

    /** 报警视频首帧地址 */
    @Excel(name = "报警视频首帧地址")
    private String videoPicture;

    /** 报警真实性 **/
    @Excel(name = "报警真实性")
    @TableField(exist = false)
    private String identify;

    /** 报警处理意见 **/
    @Excel(name = "报警处理意见")
    @TableField(exist = false)
    private String opinion;

    /** 报警设备名称 **/
    @Excel(name = "报警设备名称")
    @TableField(exist = false)
    private String deviceName;

    /** 报警详情翻页标志 **/
    @TableField(exist = false)
    private  String turnType;

    /** 机构id**/
    private Long tenantId;
    /**
     * app翻页 当前时间
     */
    @TableField(exist = false)
    private Date presentAlarmBegintime;
    /** app 报警详情图片 **/
    @TableField(exist = false)
    private ArrayList<String> picturePathList;
    /** app 报警视频**/
    @TableField(exist = false)
    private List<AlarmVideo> videoList;

    private  String alarmCid;

    private  String irmsSn ;
    /** 区域id**/
    private  String areaSn ;
    /**行业id**/
    private String sceneType;

    /**
     * 观察位置
     */
    private String targetName;
    /** 报警最高温**/
    private String maxTemp;
    /** 报警最低温**/
    private String minTemp;

    @TableField(exist = false)
    private Long[] alarmIds;

    private String remarkData;


    @Override
    public String toString() {
        return "Alarm{" +
                "alarmId=" + alarmId +
                ", deviceId=" + deviceId +
                ", deviceSn='" + deviceSn + '\'' +
                ", alarmType='" + alarmType + '\'' +
                ", alarmRank='" + alarmRank + '\'' +
                ", alarmTime='" + alarmTime + '\'' +
                ", alarmStatus='" + alarmStatus + '\'' +
                ", delFlag='" + delFlag + '\'' +
                ", alarmBegintime=" + alarmBegintime +
                ", alarmEndtime=" + alarmEndtime +
                ", picturePath='" + picturePath + '\'' +
                ", videoPath='" + videoPath + '\'' +
                ", videoPicture='" + videoPicture + '\'' +
                ", identify='" + identify + '\'' +
                ", opinion='" + opinion + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", turnType='" + turnType + '\'' +
                ", tenantId=" + tenantId +
                ", presentAlarmBegintime=" + presentAlarmBegintime +
                ", picturePathList=" + picturePathList +
                ", videoList=" + videoList +
                ", alarmCid='" + alarmCid + '\'' +
                ", irmsSn='" + irmsSn + '\'' +
                ", areaSn='" + areaSn + '\'' +
                ", sceneType='" + sceneType + '\'' +
                ", targetName='" + targetName + '\'' +
                ", maxTemp='" + maxTemp + '\'' +
                ", minTemp='" + minTemp + '\'' +
                ", alarmIds=" + Arrays.toString(alarmIds) +
                ", remarkData='" + remarkData + '\'' +
                '}';
    }
}
