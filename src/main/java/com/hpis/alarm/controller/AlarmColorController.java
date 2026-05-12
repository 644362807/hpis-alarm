package com.hpis.alarm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmColor;
import com.hpis.alarm.service.IAlarmColorService;
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
 * 报警颜色显示Controller
 * 
 * @author ds
 * @date 2024-07-03
 */
@RestController
@RequestMapping("/color")
public class AlarmColorController extends BaseController
{
    @Autowired
    private IAlarmColorService alarmColorService;

    /**
     * 查询报警颜色显示列表
     */
    @PreAuthorize(hasPermi = "alarm:color:list")
    @GetMapping("/list")
    public TableDataInfo list(AlarmColor alarmColor)
    {
//        startPage();
        Page<AlarmColor> list = alarmColorService.selectAlarmColorPage(alarmColor);
        return getDataTable(list);
    }

    /**
     * 导出报警颜色显示列表
     */
    @PreAuthorize(hasPermi = "alarm:color:export")
    @Log(title = "报警颜色显示", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AlarmColor alarmColor) throws IOException
    {
        List<AlarmColor> list = alarmColorService.selectAlarmColorList(alarmColor);
        ExcelUtil<AlarmColor> util = new ExcelUtil<AlarmColor>(AlarmColor.class);
        util.exportExcel(response, list, "color");
    }

    /**
     * 获取报警颜色显示详细信息
     */
    @PreAuthorize(hasPermi = "alarm:color:query")
    @GetMapping(value = "/{colorId}")
    public AjaxResult getInfo(@PathVariable("colorId") Long colorId)
    {
        return AjaxResult.success(alarmColorService.selectAlarmColorById(colorId));
    }

    /**
     * 新增报警颜色显示
     */
    @PreAuthorize(hasPermi = "alarm:color:add")
    @Log(title = "报警颜色显示", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AlarmColor alarmColor)
    {
        return toAjax(alarmColorService.insertAlarmColor(alarmColor));
    }

    /**
     * 修改报警颜色显示
     */
    @PreAuthorize(hasPermi = "alarm:color:edit")
    @Log(title = "报警颜色显示", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AlarmColor alarmColor)
    {
        return toAjax(alarmColorService.updateAlarmColor(alarmColor));
    }

    /**
     * 删除报警颜色显示
     */
    @PreAuthorize(hasPermi = "alarm:color:remove")
    @Log(title = "报警颜色显示", businessType = BusinessType.DELETE)
	@DeleteMapping("/{colorIds}")
    public AjaxResult remove(@PathVariable Long[] colorIds)
    {
        return toAjax(alarmColorService.deleteAlarmColorByIds(colorIds));
    }

    /**
     * 获取报警颜色显示详细信息(根据系列
     */
//    @PreAuthorize(hasPermi = "alarm:color:query")
    @GetMapping(value = "/getInfoBySeq/{sequenceUid}")
    public AjaxResult getInfoBySeq(@PathVariable("sequenceUid") String sequenceUid)
    {
        return AjaxResult.success(alarmColorService.selectAlarmColorByIrmsSn(sequenceUid));
    }
}
