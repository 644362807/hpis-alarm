package com.hpis.alarm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.api.dto.AlarmElectrolyticCellDTO;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.dto.AlarmDetailEc;
import com.hpis.alarm.dto.RepeatAlarmDto;

import javax.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * 电解槽关联报警Service接口
 * 
 * @author ruoyi
 * @date 2023-04-25
 */
public interface IAlarmElectrolyticCellService 
{

    /** 测试用接口 用于优化查询*/
    Page<AlarmDetailEc> testSelect(AlarmElectrolyticCell alarmElectrolyticCell);
    /**
     * 查询电解槽关联报警
     * 
     * @param alarmId 电解槽关联报警ID
     * @return 电解槽关联报警
     */
    public AlarmDetailEc selectAlarmElectrolyticCellById(Long alarmId);

    /**
     * 查询电解槽关联报警列表
     * 
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 电解槽关联报警集合
     */
    public Page<AlarmDetailEc> selectAlarmElectrolyticCellList(AlarmElectrolyticCell alarmElectrolyticCell);


    /**
     * 新增电解槽关联报警
     * 
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 结果
     */
    public int insertAlarmElectrolyticCell(AlarmElectrolyticCell alarmElectrolyticCell);

    /**
     * 新增电解槽关联报警-副本
     *
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 结果
     */
    public int insertAlarmElectrolyticCellEctype(AlarmElectrolyticCell alarmElectrolyticCell);


    public int insertAlarmElectrolyticCellList(List<AlarmElectrolyticCell> alarmElectrolyticCell);
    /**
     * 修改电解槽关联报警
     * 
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 结果
     */
    public int updateAlarmElectrolyticCell(AlarmElectrolyticCell alarmElectrolyticCell);

    /**
     * 批量删除电解槽关联报警
     * 
     * @param alarmIds 需要删除的电解槽关联报警ID
     * @return 结果
     */
    public int deleteAlarmElectrolyticCellByIds(Long[] alarmIds);

    /**
     * 删除电解槽关联报警信息
     * 
     * @param alarmId 电解槽关联报警ID
     * @return 结果
     */
    public int deleteAlarmElectrolyticCellEctypeById(Long alarmId);

    /**
     * 删除电解槽关联报警信息
     *
     * @param deviceSn 电解槽关联报警ID
     * @return 结果
     */
    public int deleteAlarmECEctypeByDeviceId(String deviceSn);


    /**
     * 删除电解槽关联报警信息
     *
     * @param irmsSn 电解槽关联报警ID
     * @return 结果
     */
    public int deleteAlarmECEctypeByIrmsSn(String irmsSn);
    /**
     * 导出电解槽统计报表
     * @param response
     * @param alarmElectrolyticCell
     */
    void exportAlarmStatistics(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell);

    /**
     * 导出电解槽明细报表
     * @param response
     * @param alarmElectrolyticCell
     */
    void exportAlarmRecord(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell);

    /**
     * 查询电解槽关联报警列表
     *
     * @return 电解槽关联报警集合
     */
    List<AlarmElectrolyticCellDTO> selectAlarmListByEC();

    /**
     * 根据点位查看最新事件级别
     * @param sequenceId
     * @param rowIndex
     * @param grooveNumber
     * @param observationPlace
     * @param subdivideNumber
     * @return
     */
    String selectAlarmRankByPt(Long sequenceId, Integer rowIndex, Integer grooveNumber, Integer observationPlace, Integer subdivideNumber);

    /**
     * 判断电解槽点位重复报警
     * @param alarmElectrolyticCell
     * @return
     */
    RepeatAlarmDto selectRepeatAlarmHandleByPt(AlarmElectrolyticCell alarmElectrolyticCell);

    void exportAlarmStatistics2(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell);

}
