package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONArray;
import com.hpis.alarm.domain.AlarmSend;
import com.hpis.common.core.utils.wechat.WechatAlarmData;

import java.util.HashMap;
import java.util.List;

/**
 * 推送Service接口
 *
 * @author pc
 * @date 2023-08-03
 */
public interface IAlarmSendService
{
    /**
     * 查询推送
     *
     * @param alarmSendId 推送ID
     * @return 推送
     */
    public AlarmSend selectAlarmSendById(Long alarmSendId);

    /**
     * 查询推送列表
     *
     * @param alarmSend 推送
     * @return 推送集合
     */
    public List<AlarmSend> selectAlarmSendList(AlarmSend alarmSend);

    /**
     * 新增推送
     *
     * @param alarmSend 推送
     * @return 结果
     */
    public int insertAlarmSend(AlarmSend alarmSend);

    /**
     * 修改推送
     *
     * @param alarmSend 推送
     * @return 结果
     */
    public int updateAlarmSend(AlarmSend alarmSend);

    /**
     * 批量删除推送
     *
     * @param alarmSendIds 需要删除的推送ID
     * @return 结果
     */
    public int deleteAlarmSendByIds(Long[] alarmSendIds);

    /**
     * 删除推送信息
     *
     * @param alarmSendId 推送ID
     * @return 结果
     */
    public int deleteAlarmSendById(Long alarmSendId);

    /**
     * 查询推送配置
     *
     * @param deviceId 设备id
     * @return
     */
    List<AlarmSend> selectAlarmConfigureByDeviceId(Long deviceId);

    /**
     * 发送推送消息
     * @param deviceId
     * @param type 报警类型
     * @param wechatAlarmData 消息主体
     */
    void sendRemote(Long deviceId, Long customerId, String type, WechatAlarmData wechatAlarmData);

    /**
     * 保存或更新报警推送配置
     * @param jsonArray 多个AlarmSend表单的数组
     * @return 对应配置的id表
     */
    HashMap<String, String> saveAlarmSend(JSONArray jsonArray);
}
