package com.hpis.alarm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.dto.HandleParamDto;
import com.hpis.alarm.service.IAlarmHandleService;
import com.hpis.common.core.utils.poi.ExcelUtil;
import com.hpis.common.core.web.controller.BaseController;
import com.hpis.common.core.web.domain.AjaxResult;
import com.hpis.common.core.web.page.TableDataInfo;
import com.hpis.common.log.annotation.Log;
import com.hpis.common.log.enums.BusinessType;
import com.hpis.common.security.annotation.PreAuthorize;
import com.hpis.system.api.RemoteFileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * 【请填写功能名称】Controller
 * 
 * @author ruoyi
 * @date 2023-03-24
 */
@RestController
@RequestMapping("/handle")
public class AlarmHandleController extends BaseController
{
    @Autowired
    private IAlarmHandleService alarmHandleService;

    @Autowired
    private RemoteFileService remoteFileService;

    /**
     * 查询【请填写功能名称】列表
     */
    @PreAuthorize(hasPermi = "alarm:handle:list")
    @GetMapping("/list")
    public TableDataInfo list(AlarmHandle alarmHandle)
    {
        Page<AlarmHandle> list = alarmHandleService.selectAlarmHandlePage(alarmHandle);
        return getDataTable(list);
    }

    /**
     * 导出【请填写功能名称】列表
     */
    @PreAuthorize(hasPermi = "alarm:handle:export")
    @Log(title = "【请填写功能名称】", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AlarmHandle alarmHandle) throws IOException
    {
        List<AlarmHandle> list = alarmHandleService.selectAlarmHandleList(alarmHandle);
        ExcelUtil<AlarmHandle> util = new ExcelUtil<AlarmHandle>(AlarmHandle.class);
        util.exportExcel(response, list, "handle");
    }

    /**
     * 获取【请填写功能名称】详细信息
     */
    @PreAuthorize(hasPermi = "alarm:handle:query")
    @GetMapping(value = "/{alarmHandleId}")
    public AjaxResult getInfo(@PathVariable("alarmHandleId") Long alarmHandleId)
    {
        return AjaxResult.success(alarmHandleService.selectAlarmHandleById(alarmHandleId));
    }

    /**
     * 新增【请填写功能名称】
     */
    @PreAuthorize(hasPermi = "alarm:handle:add")
    @Log(title = "【请填写功能名称】", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AlarmHandle alarmHandle)
    {
        return toAjax(alarmHandleService.insertAlarmHandle(alarmHandle));
    }

    /**
     * 通用接口
     * 保存报警处理
     */
//    @PreAuthorize(hasPermi = "alarm:handle:edit")
    @Log(title = "保存报警处理", businessType = BusinessType.UPDATE)
    @PostMapping("/save")
    public AjaxResult edit(@RequestBody HandleParamDto handleParamDto)
    {
        return toAjax(alarmHandleService.saveAlarmHandle(handleParamDto));
    }


    /**
     * 电解槽接口
     * 保存报警处理
     */
//    @PreAuthorize(hasPermi = "alarm:handle:edit")
    @Log(title = "保存报警处理", businessType = BusinessType.UPDATE)
    @GetMapping("/saveAll")
    public AjaxResult edit1( HandleParamDto handleParamDto)
    {
        return toAjax(alarmHandleService.saveAlarmAllHandle(handleParamDto));
    }


    /**
     * 报警处理修改（确定）
     */
//    @PreAuthorize(hasPermi = "alarm:handle:confirm")
    @Log(title = "保存报警处理", businessType = BusinessType.UPDATE)
    @PostMapping("/update")
    public AjaxResult confirm(@RequestBody AlarmHandle alarmHandle)
    {

        return toAjax(alarmHandleService.updateAlarmHandle(alarmHandle));
    }


    /**
     * 删除【请填写功能名称】
     */
    @PreAuthorize(hasPermi = "alarm:handle:remove")
    @Log(title = "【请填写功能名称】", businessType = BusinessType.DELETE)
	@DeleteMapping("/delete/{alarmHandleIds}")
    public AjaxResult remove(@PathVariable Long[] alarmHandleIds)
    {
        System.out.println(alarmHandleIds);
        return toAjax(alarmHandleService.deleteAlarmHandleByIds(alarmHandleIds));
    }
}