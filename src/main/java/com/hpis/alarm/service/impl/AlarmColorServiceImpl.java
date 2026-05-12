package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpis.alarm.domain.AlarmColor;
import com.hpis.alarm.mapper.AlarmColorMapper;
import com.hpis.alarm.service.IAlarmColorService;
import com.hpis.common.core.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 报警颜色显示Service业务层处理
 * 
 * @author ds
 * @date 2024-07-03
 */
@Service
public class AlarmColorServiceImpl extends ServiceImpl<AlarmColorMapper, AlarmColor> implements IAlarmColorService
{
    @Autowired
    private AlarmColorMapper alarmColorMapper;

    /**
     * 查询报警颜色显示
     * 
     * @param colorId 报警颜色显示ID
     * @return 报警颜色显示
     */
    @Override
    public AlarmColor selectAlarmColorById(Long colorId)
    {
        return alarmColorMapper.selectAlarmColorById(colorId);
    }

    @Override
    public Page<AlarmColor> selectAlarmColorPage(AlarmColor alarmColor) {
        LambdaQueryWrapper<AlarmColor> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotBlank(alarmColor.getIrmsSn()),AlarmColor::getIrmsSn,alarmColor.getIrmsSn());
        return null;
    }

    /**
     * 查询报警颜色显示列表
     * 
     * @param alarmColor 报警颜色显示
     * @return 报警颜色显示
     */
    @Override
    public List<AlarmColor> selectAlarmColorList(AlarmColor alarmColor)
    {
        return alarmColorMapper.selectAlarmColorList(alarmColor);
    }

    /**
     * 新增报警颜色显示
     * 
     * @param alarmColor 报警颜色显示
     * @return 结果
     */
    @Override
    public int insertAlarmColor(AlarmColor alarmColor)
    {
        return alarmColorMapper.insertAlarmColor(alarmColor);
    }

    /**
     * 修改报警颜色显示
     * 
     * @param alarmColor 报警颜色显示
     * @return 结果
     */
    @Override
    public int updateAlarmColor(AlarmColor alarmColor)
    {
        return alarmColorMapper.updateAlarmColor(alarmColor);
    }

    /**
     * 批量删除报警颜色显示
     * 
     * @param colorIds 需要删除的报警颜色显示ID
     * @return 结果
     */
    @Override
    public int deleteAlarmColorByIds(Long[] colorIds)
    {
        return alarmColorMapper.deleteAlarmColorByIds(colorIds);
    }

    /**
     * 删除报警颜色显示信息
     * 
     * @param colorId 报警颜色显示ID
     * @return 结果
     */
    @Override
    public int deleteAlarmColorById(Long colorId)
    {
        return alarmColorMapper.deleteAlarmColorById(colorId);
    }

    @Override
    public int insertOrUpdateAlarmColor(JSONObject jsonObject) {
        AlarmColor alarmColor = JSONObject.parseObject(jsonObject.toJSONString(), AlarmColor.class);
        AlarmColor ac = this.getOne(Wrappers.lambdaQuery(AlarmColor.class).eq(AlarmColor::getIrmsSn,alarmColor.getIrmsSn()));
        if (ac == null) {
            this.save(alarmColor);
        } else {
            alarmColor.setColorId(ac.getColorId());
            alarmColorMapper.updateById(alarmColor);
        }
        return 1;
    }

    @Override
    public AlarmColor selectAlarmColorByIrmsSn(String irmsSn) {
        return this.getOne(Wrappers.lambdaQuery(AlarmColor.class).eq(AlarmColor::getIrmsSn,irmsSn));
    }
}
