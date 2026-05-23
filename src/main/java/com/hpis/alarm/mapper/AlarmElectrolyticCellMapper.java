package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.api.dto.AlarmElectrolyticCellDTO;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.dto.AlarmDetailEc;
import com.hpis.alarm.dto.AlarmElectrolyticCellRecord;
import com.hpis.alarm.dto.RepeatAlarmDto;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 电解槽关联报警Mapper接口
 * 
 * @author ruoyi
 * @date 2023-04-25
 */
public interface AlarmElectrolyticCellMapper  extends BaseMapper<AlarmElectrolyticCell>
{
    /**
     * 查询电解槽关联报警
     * 
     * @param alarmId 电解槽关联报警ID
     * @return 电解槽关联报警
     */
    public AlarmElectrolyticCell selectAlarmElectrolyticCellById(Long alarmId);

    /**
     * 查询电解槽关联报警
     *
     * @param alarmId 电解槽关联报警ID
     * @return 电解槽关联报警
     */
    public AlarmElectrolyticCellDTO selectAlarmElectrolyticCellDetailById(Long alarmId);

    /**
     * 查询电解槽关联报警列表
     * 
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 电解槽关联报警集合
     */
    public List<AlarmElectrolyticCellDTO> selectAlarmElectrolyticCellList(AlarmElectrolyticCell alarmElectrolyticCell);

    /**
     * 查询电解槽关联报警列表
     *
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 电解槽关联报警集合
     */
    public List<AlarmDetailEc> selectAlarmAlarmDetailEcList(AlarmElectrolyticCell alarmElectrolyticCell);


    Page<AlarmDetailEc> selectECPage(Page rowBounds, @Param("ew") Wrapper<AlarmElectrolyticCell> wrapper);

    /**
     * 查询电解槽关联报警的明细
     * @param alarmElectrolyticCell
     * @return
     */
    List<AlarmElectrolyticCellRecord> selectAlarmElectrolyticCellRecordList(AlarmElectrolyticCell alarmElectrolyticCell);

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


    /**
     * 批量插入电解槽主扩展表。
     *
     * <p>仅用于 insert prepared context 批量持久化，必须和主报警表处于同一事务，
     * 避免主表成功但电解槽扩展缺失。</p>
     */
    int insertAlarmElectrolyticCellList(List<AlarmElectrolyticCell> alarmElectrolyticCells);

    /**
     * 批量插入电解槽 ectype 副表。
     *
     * <p>该副表后续会被消警 side effect 清理，批量写入必须保持 alarmId 与主报警一致。</p>
     */
    int insertAlarmElectrolyticCellEctypeList(@Param("items") List<AlarmElectrolyticCell> alarmElectrolyticCells);

    int deleteOldAlarmEctypeByItems(@Param("items") List<AlarmElectrolyticCell> alarmElectrolyticCells);

    /**
     * 修改电解槽关联报警
     * 
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 结果
     */
    public int updateAlarmElectrolyticCell(AlarmElectrolyticCell alarmElectrolyticCell);

    /**
     * 删除电解槽关联报警-副表
     * 
     * @param alarmId 电解槽关联报警ID
     * @return 结果
     */
    public int deleteAlarmElectrolyticCellEctypeById(Long alarmId);

    /**
     * 删除电解槽关联报警-副表
     *
     * @param deviceSn 电解槽关联报警ID
     * @return 结果
     */
    public int deleteAlarmECEctypeByDeviceId(String deviceSn);


    /**
     * 删除电解槽关联报警-副表
     *
     * @param irmsSn 电解槽关联报警ID
     * @return 结果
     */
    public int deleteAlarmECEctypeByIrmsSn(String irmsSn);
    /**
     * 批量删除电解槽关联报警
     * 
     * @param alarmIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmElectrolyticCellByIds(Long[] alarmIds);

    /**
     * 根据点位查看最新事件级别
     * @param sequenceId
     * @param rowIndex
     * @param grooveNumber
     * @param observationPlace
     * @param subdivideNumber
     * @return
     */
    String selectAlarmRankByPt(@Param("sequenceId") Long sequenceId, @Param("rowIndex") Integer rowIndex,
                               @Param("grooveNumber") Integer grooveNumber, @Param("observationPlace") Integer observationPlace,
                               @Param("subdivideNumber") Integer subdivideNumber);

    /**
     * 判断电解槽点位重复报警
     * @param alarmElectrolyticCell
     * @return
     */
    RepeatAlarmDto selectRepeatAlarmHandleByPt(AlarmElectrolyticCell alarmElectrolyticCell);

    /**
     * 删除同点位旧报警
     * @param sequenceId
     * @param rowIndex
     * @param grooveNumber
     * @param observationPlace
     * @param subdivideNumber
     */
    void deleteOldAlarmEctypeByPt(@Param("sequenceId") String sequenceId, @Param("rowIndex") Integer rowIndex,
                             @Param("grooveNumber") Integer grooveNumber, @Param("observationPlace") String observationPlace,
                             @Param("subdivideNumber") Integer subdivideNumber,@Param("irmsSn") String irmsSn);


    /**
     * 查询电解槽关联报警列表-点位最新报警
     *
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 电解槽关联报警集合
     */
    public List<AlarmElectrolyticCellDTO> selectNewAlarmElectrolyticCellList();
}
