package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpis.alarm.domain.AlarmColor;

import java.util.List;

/**
 * 报警颜色显示Mapper接口
 * 
 * @author ds
 * @date 2024-07-03
 */
public interface AlarmColorMapper extends BaseMapper<AlarmColor>
{
    /**
     * 查询报警颜色显示
     * 
     * @param colorId 报警颜色显示ID
     * @return 报警颜色显示
     */
    public AlarmColor selectAlarmColorById(Long colorId);

    /**
     * 查询报警颜色显示
     *
     * @param irmsSn
     * @return 报警颜色显示
     */
    public AlarmColor selectAlarmColorByIrmsSn(String irmsSn);

    /**
     * 查询报警颜色显示列表
     * 
     * @param alarmColor 报警颜色显示
     * @return 报警颜色显示集合
     */
    public List<AlarmColor> selectAlarmColorList(AlarmColor alarmColor);

    /**
     * 新增报警颜色显示
     * 
     * @param alarmColor 报警颜色显示
     * @return 结果
     */
    public int insertAlarmColor(AlarmColor alarmColor);

    /**
     * 修改报警颜色显示
     * 
     * @param alarmColor 报警颜色显示
     * @return 结果
     */
    public int updateAlarmColor(AlarmColor alarmColor);

    /**
     * 删除报警颜色显示
     * 
     * @param colorId 报警颜色显示ID
     * @return 结果
     */
    public int deleteAlarmColorById(Long colorId);

    /**
     * 批量删除报警颜色显示
     * 
     * @param colorIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmColorByIds(Long[] colorIds);
}
