package com.hpis.alarm.service.impl;

import com.hpis.alarm.domain.SoundAlarm;
import com.hpis.alarm.mapper.SoundAlarmMapper;
import com.hpis.alarm.service.ISoundAlarmService;
import com.hpis.common.core.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 声音报警器管理Service业务层处理
 *
 * @author pc
 * @date 2023-08-18
 */
@Service
public class SoundAlarmServiceImpl implements ISoundAlarmService
{
    @Autowired
    private SoundAlarmMapper soundAlarmMapper;

    /**
     * 查询声音报警器管理
     *
     * @param soundId 声音报警器管理ID
     * @return 声音报警器管理
     */
    @Override
    public SoundAlarm selectSoundAlarmById(Long soundId)
    {
        return soundAlarmMapper.selectSoundAlarmById(soundId);
    }

    /**
     * 查询声音报警器管理列表
     *
     * @param soundAlarm 声音报警器管理
     * @return 声音报警器管理
     */
    @Override
    public List<SoundAlarm> selectSoundAlarmList(SoundAlarm soundAlarm)
    {
        return soundAlarmMapper.selectSoundAlarmList(soundAlarm);
    }

    /**
     * 新增声音报警器管理
     *
     * @param soundAlarm 声音报警器管理
     * @return 结果
     */
    @Override
    public int insertSoundAlarm(SoundAlarm soundAlarm)
    {
        soundAlarm.setCreateTime(DateUtils.getNowDate());
        return soundAlarmMapper.insertSoundAlarm(soundAlarm);
    }

    /**
     * 修改声音报警器管理
     *
     * @param soundAlarm 声音报警器管理
     * @return 结果
     */
    @Override
    public int updateSoundAlarm(SoundAlarm soundAlarm)
    {
        soundAlarm.setUpdateTime(DateUtils.getNowDate());
        return soundAlarmMapper.updateSoundAlarm(soundAlarm);
    }

    /**
     * 批量删除声音报警器管理
     *
     * @param soundIds 需要删除的声音报警器管理ID
     * @return 结果
     */
    @Override
    public int deleteSoundAlarmByIds(Long[] soundIds)
    {
        return soundAlarmMapper.deleteSoundAlarmByIds(soundIds);
    }

    /**
     * 删除声音报警器管理信息
     *
     * @param soundId 声音报警器管理ID
     * @return 结果
     */
    @Override
    public int deleteSoundAlarmById(Long soundId)
    {
        return soundAlarmMapper.deleteSoundAlarmById(soundId);
    }

    @Override
    public SoundAlarm selectSoundAlarmByLocation(SoundAlarm soundAlarm) {
        return soundAlarmMapper.selectSoundAlarmByLocation(soundAlarm);
    }
}
