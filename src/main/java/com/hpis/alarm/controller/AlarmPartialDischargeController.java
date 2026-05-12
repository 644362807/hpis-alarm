package com.hpis.alarm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmPartialDischarge;
import com.hpis.alarm.dto.AlarmPartialDischargeDto;
import com.hpis.alarm.dto.AlarmQueryParameter;
import com.hpis.alarm.service.IAlarmPartialDischargeService;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.poi.ExcelUtil;
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
 * 局放报警详情Controller
 * 
 * @author ruoyi
 * @date 2024-03-13
 */
@RestController
@RequestMapping("/discharge")
public class AlarmPartialDischargeController extends BaseController
{
    @Autowired
    private IAlarmPartialDischargeService alarmPartialDischargeService;

    /**
     * 查询局放报警详情列表
     */
//    @PreAuthorize(hasPermi = "discharge:discharge:list")
    @GetMapping("/list")
    public TableDataInfo list(AlarmPartialDischarge alarmPartialDischarge)
    {
        alarmPartialDischarge.setTenantId(SecurityUtils.getCurrentTenantId());
        Page<AlarmPartialDischargeDto> list = alarmPartialDischargeService.selectAlarmPartialDischargePage(alarmPartialDischarge);
        return getDataTable(list);
    }

    /**
     * 在线局放顶部统计
     * @param alarmPartialDischarge
     * @return
     */
    @PostMapping("/topCount")
    public AjaxResult partialDischargeCount(@RequestBody AlarmPartialDischarge alarmPartialDischarge){
        alarmPartialDischarge.setTenantId(SecurityUtils.getCurrentTenantId());
        return AjaxResult.success( alarmPartialDischargeService.partialDischargeCount(alarmPartialDischarge));
    }

    /**
     * 在线局放类型报警
     * @param alarmQueryParameter
     * @return
     */
    @PostMapping("/typeCount")
    public AjaxResult detectionModeCount(@RequestBody AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setTenantId(SecurityUtils.getCurrentTenantId());
        return AjaxResult.success( alarmPartialDischargeService.detectionModeCount(alarmQueryParameter));
    }

    /**
     * 在线局放通道报警
     * @param alarmQueryParameter
     * @return
     */
    @PostMapping("/channelAlarmCount")
    public AjaxResult channelAlarmCount(@RequestBody AlarmQueryParameter alarmQueryParameter){
        return AjaxResult.success( alarmPartialDischargeService.channelModeCount(alarmQueryParameter));
    }

    /**
     * 根据客户查看设备报警数
     * @param alarmQueryParameter
     * @return
     */
    @PostMapping("/deviceAlarmCount")
    public AjaxResult deviceAlarmCount(@RequestBody AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setTenantId(SecurityUtils.getCurrentTenantId());
        return AjaxResult.success( alarmPartialDischargeService.deviceAlarm(alarmQueryParameter));
    }
    /**
     * 七天内报警的放电类型统计占比
     * @param alarmQueryParameter
     * @return
     */
    @PostMapping("/alarmDPType")
    public AjaxResult alarmDPType(@RequestBody AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setTenantId(SecurityUtils.getCurrentTenantId());
        return AjaxResult.success( alarmPartialDischargeService.alarmDPType(alarmQueryParameter));
    }


    /**
     * 客户下所有设备每天的报警总数
     * @param alarmQueryParameter
     * @return
     */
    @PostMapping("/deviceAlarmOfDayByCustomer")
    public AjaxResult deviceAlarmOfDayByCustomer(@RequestBody AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setTenantId(SecurityUtils.getCurrentTenantId());
        return AjaxResult.success( alarmPartialDischargeService.deviceAlarmOfDayByCustomer(alarmQueryParameter));
    }

    /**
     * 导出局放报警详情列表
     */
    @PreAuthorize(hasPermi = "discharge:discharge:export")
    @Log(title = "局放报警详情", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AlarmPartialDischarge alarmPartialDischarge) throws IOException
    {
        List<AlarmPartialDischargeDto> list = alarmPartialDischargeService.selectAlarmPartialDischargeList(alarmPartialDischarge);
        ExcelUtil<AlarmPartialDischargeDto> util = new ExcelUtil<AlarmPartialDischargeDto>(AlarmPartialDischargeDto.class);
        util.exportExcel(response, list, "discharge");
    }

    /**
     * 获取局放报警详情详细信息
     */
//    @PreAuthorize(hasPermi = "discharge:discharge:query")
    @GetMapping(value = "/{alarmId}")
    public AjaxResult getInfo(@PathVariable("alarmId") Long alarmId)
    {
        return AjaxResult.success(alarmPartialDischargeService.selectAlarmPartialDischargeById(alarmId));
    }


    /**
     * 新增局放报警详情
     */
    @PreAuthorize(hasPermi = "discharge:discharge:add")
    @Log(title = "局放报警详情", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AlarmPartialDischarge alarmPartialDischarge)
    {
        alarmPartialDischarge.setCreateBy(SecurityUtils.getUsername());
        return toAjax(alarmPartialDischargeService.insertAlarmPartialDischarge(alarmPartialDischarge));
    }

    /**
     * 修改局放报警详情
     */
    @PreAuthorize(hasPermi = "discharge:discharge:edit")
    @Log(title = "局放报警详情", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AlarmPartialDischarge alarmPartialDischarge)
    {
        alarmPartialDischarge.setUpdateBy(SecurityUtils.getUsername());
        return toAjax(alarmPartialDischargeService.updateAlarmPartialDischarge(alarmPartialDischarge));
    }

    /**
     * 删除局放报警详情
     */
    @PreAuthorize(hasPermi = "discharge:discharge:remove")
    @Log(title = "局放报警详情", businessType = BusinessType.DELETE)
	@DeleteMapping("/{alarmIds}")
    public AjaxResult remove(@PathVariable Long[] alarmIds)
    {
        return toAjax(alarmPartialDischargeService.deleteAlarmPartialDischargeByIds(alarmIds));
    }
}