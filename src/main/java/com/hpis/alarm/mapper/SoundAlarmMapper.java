package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.SoundAlarm;

import java.util.List;

/**
 * 声音报警器管理Mapper接口
 *
 * @author pc
 * @date 2023-08-18
 */
public interface SoundAlarmMapper
{
    /**
     * 查询声音报警器管理
     *
     * @param soundId 声音报警器管理ID
     * @return 声音报警器管理
     */
    public SoundAlarm selectSoundAlarmById(Long soundId);

    /**
     * 查询声音报警器管理列表
     *
     * @param soundAlarm 声音报警器管理
     * @return 声音报警器管理集合
     */
    public List<SoundAlarm> selectSoundAlarmList(SoundAlarm soundAlarm);

    /**
     * 新增声音报警器管理
     *
     * @param soundAlarm 声音报警器管理
     * @return 结果
     */
    public int insertSoundAlarm(SoundAlarm soundAlarm);

    /**
     * 修改声音报警器管理
     *
     * @param soundAlarm 声音报警器管理
     * @return 结果
     */
    public int updateSoundAlarm(SoundAlarm soundAlarm);

    /**
     * 删除声音报警器管理
     *
     * @param soundId 声音报警器管理ID
     * @return 结果
     */
    public int deleteSoundAlarmById(Long soundId);

    /**
     * 批量删除声音报警器管理
     *
     * @param soundIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteSoundAlarmByIds(Long[] soundIds);

    SoundAlarm selectSoundAlarmByLocation(SoundAlarm soundAlarm);
}
