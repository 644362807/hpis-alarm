package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmSendLog;

import java.util.List;

/**
 * 推送记录Mapper接口
 *
 * @author pc
 * @date 2023-08-09
 */
public interface AlarmSendLogMapper
{
    /**
     * 查询推送记录
     *
     * @param sendLogId 推送记录ID
     * @return 推送记录
     */
    public AlarmSendLog selectAlarmSendLogById(Long sendLogId);

    /**
     * 查询推送记录列表
     *
     * @param alarmSendLog 推送记录
     * @return 推送记录集合
     */
    public List<AlarmSendLog> selectAlarmSendLogList(AlarmSendLog alarmSendLog);

    /**
     * 新增推送记录
     *
     * @param alarmSendLog 推送记录
     * @return 结果
     */
    public int insertAlarmSendLog(AlarmSendLog alarmSendLog);

    /**
     * 修改推送记录
     *
     * @param alarmSendLog 推送记录
     * @return 结果
     */
    public int updateAlarmSendLog(AlarmSendLog alarmSendLog);

    /**
     * 删除推送记录
     *
     * @param sendLogId 推送记录ID
     * @return 结果
     */
    public int deleteAlarmSendLogById(Long sendLogId);

    /**
     * 批量删除推送记录
     *
     * @param sendLogIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmSendLogByIds(Long[] sendLogIds);
}
