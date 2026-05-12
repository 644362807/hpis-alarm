package com.hpis.alarm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.api.dto.AlarmElectrolyticCellDTO;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.dto.AlarmDetailEc;
import com.hpis.alarm.service.IAlarmElectrolyticCellService;
import com.hpis.common.core.domain.R;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.web.controller.BaseController;
import com.hpis.common.core.web.domain.AjaxResult;
import com.hpis.common.core.web.page.TableDataInfo;
import com.hpis.common.log.annotation.Log;
import com.hpis.common.log.enums.BusinessType;
import com.hpis.common.security.annotation.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * 电解槽关联报警Controller
 * 
 * @author ruoyi
 * @date 2023-04-25
 */
@RestController
@RequestMapping("/cell")
public class AlarmElectrolyticCellController extends BaseController
{
    @Autowired
    private IAlarmElectrolyticCellService alarmElectrolyticCellService;

    /**
     * 查询电解槽关联报警列表
     */
    @PreAuthorize(hasPermi = "alarm:cell:list")
    @GetMapping("/list")
    public TableDataInfo list(AlarmElectrolyticCell alarmElectrolyticCell)
    {
//        startPage();
        Page<AlarmDetailEc> list = alarmElectrolyticCellService.selectAlarmElectrolyticCellList(alarmElectrolyticCell);
        return getDataTable(list);
    }


    /**
     * 导出槽面发热点统计表
     */
    @PreAuthorize(hasPermi = "alarm:cell:export")
    @Log(title = "电解槽关联报警", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell) throws IOException {
        alarmElectrolyticCellService.exportAlarmStatistics(response, alarmElectrolyticCell);
    }

    /**
     * 导出槽面发热点明细表
     */
    @PreAuthorize(hasPermi = "alarm:cell:exportRecord")
    @Log(title = "电解槽关联报警", businessType = BusinessType.EXPORT)
    @PostMapping("/exportRecord")
    public void exportRecord(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell) throws IOException {
        alarmElectrolyticCellService.exportAlarmRecord(response, alarmElectrolyticCell);
    }


    /**
     * 获取电解槽关联报警详细信息
     */
    @GetMapping(value = "/{alarmId}")
    public AjaxResult getInfo(@PathVariable("alarmId") Long alarmId)
    {
        return AjaxResult.success(alarmElectrolyticCellService.selectAlarmElectrolyticCellById(alarmId));
    }

    /**
     * 新增电解槽关联报警
     */
    @PreAuthorize(hasPermi = "alarm:cell:add")
    @Log(title = "电解槽关联报警", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AlarmElectrolyticCell alarmElectrolyticCell)
    {
        alarmElectrolyticCell.setCreateBy(SecurityUtils.getUsername());
        return toAjax(alarmElectrolyticCellService.insertAlarmElectrolyticCell(alarmElectrolyticCell));
    }

    /**
     * 修改电解槽关联报警
     */
    @PreAuthorize(hasPermi = "alarm:cell:edit")
    @Log(title = "电解槽关联报警", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AlarmElectrolyticCell alarmElectrolyticCell)
    {
        alarmElectrolyticCell.setUpdateBy(SecurityUtils.getUsername());
        return toAjax(alarmElectrolyticCellService.updateAlarmElectrolyticCell(alarmElectrolyticCell));
    }

    /**
     * 删除电解槽关联报警
     */
    @PreAuthorize(hasPermi = "alarm:cell:remove")
    @Log(title = "电解槽关联报警", businessType = BusinessType.DELETE)
	@DeleteMapping("/{alarmIds}")
    public AjaxResult remove(@PathVariable Long[] alarmIds)
    {
        return toAjax(alarmElectrolyticCellService.deleteAlarmElectrolyticCellByIds(alarmIds));
    }

    /**
     * 查询电解槽关联报警列表
     *
     * @param dto 电解槽关联报警
     * @return 电解槽关联报警集合
     */
    @PostMapping(value = "/selectAlarmListByEC")
    public R<List<AlarmElectrolyticCellDTO>> selectAlarmListByEC() {
        return R.ok(alarmElectrolyticCellService.selectAlarmListByEC());
    }

    /**
     * 根据点位查看最新事件级别(远程调用）
     * @param sequenceId
     * @param rowIndex
     * @param grooveNumber
     * @param observationPlace
     * @param subdivideNumber
     * @return
     */
    @PostMapping(value = "/selectAlarmRankByPt")
    public R<String> selectAlarmRankByPt(@RequestParam("sequenceId") Long sequenceId, @RequestParam("rowIndex") Integer rowIndex,
                                         @RequestParam("grooveNumber") Integer grooveNumber, @RequestParam("observationPlace") Integer observationPlace,
                                         @RequestParam(value = "subdivideNumber",required = false) Integer subdivideNumber) {
        return R.ok(alarmElectrolyticCellService.selectAlarmRankByPt(sequenceId, rowIndex, grooveNumber, observationPlace, subdivideNumber));
    }

    /**
     * 导出槽面发热点统计表(第二张报表）
     */
//    @PreAuthorize(hasPermi = "alarm:cell:exportHotStatistics")
    @Log(title = "电解槽关联报警", businessType = BusinessType.EXPORT)
    @PostMapping("/exportHotStatistics")
    public void exportHotStatistics(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell) throws IOException {
        alarmElectrolyticCellService.exportAlarmStatistics2(response, alarmElectrolyticCell);
    }


}
