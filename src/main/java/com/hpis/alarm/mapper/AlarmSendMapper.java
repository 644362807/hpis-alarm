package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmSend;

import java.util.List;

/**
 * 推送Mapper接口
 *
 * @author pc
 * @date 2023-08-03
 */
public interface AlarmSendMapper
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
     * 删除推送
     *
     * @param alarmSendId 推送ID
     * @return 结果
     */
    public int deleteAlarmSendById(Long alarmSendId);

    /**
     * 批量删除推送
     *
     * @param alarmSendIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmSendByIds(Long[] alarmSendIds);

    List<AlarmSend> selectAlarmConfigureByDeviceId(Long deviceId);
}
