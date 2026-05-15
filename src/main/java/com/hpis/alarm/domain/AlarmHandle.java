package com.hpis.alarm.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.hpis.common.core.annotation.Excel;
import com.hpis.common.core.web.domain.BaseEntity;
import org.springframework.data.annotation.Transient;

import java.util.Date;

/**
 * 【请填写功能名称】对象 alarm_handle
 *
 * @author ruoyi
 * @date 2023-03-24
 */

public class AlarmHandle extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 报警处理id */
    private Long alarmHandleId;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    /** 唯一警报id */
    @Excel(name = "唯一警报id")
    @TableId(type = IdType.AUTO)
    private Long alarmId;

    /** 报警处理人id */
    @Excel(name = "报警处理人id")
    private Long handlerId;

    /** 报警处理人顺序 */
    @Excel(name = "报警处理人顺序")
    private String handleUserOrder;

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

    public Long[] getAlarmIds() {
        return alarmIds;
    }

    public void setAlarmIds(Long[] alarmIds) {
        this.alarmIds = alarmIds;
    }

    @Transient
    public String getSceneType() {
        return sceneType;
    }

    public void setSceneType(String sceneType) {
        this.sceneType = sceneType;
    }

    public Long getAlarmHandleId() {
        return alarmHandleId;
    }

    public void setAlarmHandleId(Long alarmHandleId) {
        this.alarmHandleId = alarmHandleId;
    }

    public Long getAlarmId() {
        return alarmId;
    }

    public void setAlarmId(Long alarmId) {
        this.alarmId = alarmId;
    }

    public Long getHandlerId() {
        return handlerId;
    }

    public void setHandlerId(Long handlerId) {
        this.handlerId = handlerId;
    }

    public String getHandleUserOrder() {
        return handleUserOrder;
    }

    public void setHandleUserOrder(String handleUserOrder) {
        this.handleUserOrder = handleUserOrder;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Long deviceId) {
        this.deviceId = deviceId;
    }

    public String getAlarmType() {
        return alarmType;
    }

    public void setAlarmType(String alarmType) {
        this.alarmType = alarmType;
    }

    public String getAlarmRank() {
        return alarmRank;
    }

    public void setAlarmRank(String alarmRank) {
        this.alarmRank = alarmRank;
    }

    public String getAlarmStatus() {
        return alarmStatus;
    }

    public void setAlarmStatus(String alarmStatus) {
        this.alarmStatus = alarmStatus;
    }

    public String getHandleStatus() {
        return handleStatus;
    }

    public void setHandleStatus(String handleStatus) {
        this.handleStatus = handleStatus;
    }

    public Date getAlarmBegintime() {
        return alarmBegintime;
    }

    public void setAlarmBegintime(Date alarmBegintime) {
        this.alarmBegintime = alarmBegintime;
    }

    public Date getAlarmEndtime() {
        return alarmEndtime;
    }

    public void setAlarmEndtime(Date alarmEndtime) {
        this.alarmEndtime = alarmEndtime;
    }

    public String getDelFlag() {
        return delFlag;
    }

    public void setDelFlag(String delFlag) {
        this.delFlag = delFlag;
    }

    public String getIdentify() {
        return identify;
    }

    public void setIdentify(String identify) {
        this.identify = identify;
    }

    public String getOpinion() {
        return opinion;
    }

    public void setOpinion(String opinion) {
        this.opinion = opinion;
    }

    public String getIrmsSn() {
        return irmsSn;
    }

    public void setIrmsSn(String irmsSn) {
        this.irmsSn = irmsSn;
    }

    public String getAreaSn() {
        return areaSn;
    }

    public void setAreaSn(String areaSn) {
        this.areaSn = areaSn;
    }

    public String getTargetName() {
        return targetName;
    }

    public void setTargetName(String targetName) {
        this.targetName = targetName;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }


    public Date getHandleTime() {
        return handleTime;
    }

    public void setHandleTime(Date handleTime) {
        this.handleTime = handleTime;
    }

    public String getHandlePicture() {
        return handlePicture;
    }

    public void setHandlePicture(String handlePicture) {
        this.handlePicture = handlePicture;
    }


    public Long getConfirmUserId() {
        return confirmUserId;
    }

    public void setConfirmUserId(Long confirmUserId) {
        this.confirmUserId = confirmUserId;
    }

    public String getApparatusId() {
        return apparatusId;
    }

    public void setApparatusId(String apparatusId) {
        this.apparatusId = apparatusId;
    }

    public String getHandlerName() {
        return handlerName;
    }

    public void setHandlerName(String handlerName) {
        this.handlerName = handlerName;
    }

    @Override
    public String toString() {
        return "AlarmHandle{" +
                "alarmHandleId=" + alarmHandleId +
                ", alarmId=" + alarmId +
                ", handlerId=" + handlerId +
                ", handleUserOrder='" + handleUserOrder + '\'' +
                ", deviceId=" + deviceId +
                ", alarmType='" + alarmType + '\'' +
                ", alarmRank='" + alarmRank + '\'' +
                ", alarmStatus='" + alarmStatus + '\'' +
                ", handleStatus='" + handleStatus + '\'' +
                ", alarmBegintime=" + alarmBegintime +
                ", alarmEndtime=" + alarmEndtime +
                ", handleTime=" + handleTime +
                ", delFlag='" + delFlag + '\'' +
                ", identify='" + identify + '\'' +
                ", opinion='" + opinion + '\'' +
                ", irmsSn='" + irmsSn + '\'' +
                ", areaSn='" + areaSn + '\'' +
                ", targetName='" + targetName + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", customerId='" + customerId + '\'' +
                ", sceneType='" + sceneType + '\'' +
                ", handlePicture='" + handlePicture + '\'' +
                ", confirmUserId=" + confirmUserId +
                ", apparatusId='" + apparatusId + '\'' +
                ", handlerName='" + handlerName + '\'' +
                '}';
    }
}
