package com.hpis.alarm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.enums.SceneTypeEnums;
import com.hpis.alarm.service.IAlarmConfigureService;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.poi.ExcelUtil;
import com.hpis.common.core.web.controller.BaseController;
import com.hpis.common.core.web.domain.AjaxResult;
import com.hpis.common.core.web.page.TableDataInfo;
import com.hpis.common.log.annotation.Log;
import com.hpis.common.log.enums.BusinessType;
import com.hpis.common.security.annotation.PreAuthorize;
import com.hpis.common.security.service.TokenService;
import com.hpis.system.api.model.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.ParseException;
import java.util.List;

/**
 * 报警配置Controller
 *
 * @author 向文来
 * @date 2023-03-28
 */
@RestController
@RequestMapping("/configure")
public class AlarmConfigureController extends BaseController
{
    @Autowired
    private IAlarmConfigureService alarmConfigureService;

    @Autowired
    private TokenService tokenService;

    /**
     * 查询报警配置列表
     */
    @PreAuthorize(hasPermi = "alarm_configure:configure:list")
    @GetMapping("/list")
    public TableDataInfo list(AlarmConfigure alarmConfigure)
    {
//        LoginUser userInfo = tokenService.getLoginUser();
        Page<AlarmConfigure> list = alarmConfigureService.selectAlarmConfigurePage(alarmConfigure);
        return getDataTable(list);
    }

    /**
     * 查询报警配置列表
     */
    @GetMapping("/repeatConfig")
    public AjaxResult repeatConfig(AlarmConfigure alarmConfigure)
    {
//        LoginUser userInfo = tokenService.getLoginUser();

        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        alarmConfigure.setTenantId(currentTenantId);
        alarmConfigure.setSceneType(SceneTypeEnums.SCENE_TYPE_2.getKey()+"");
        alarmConfigure.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey());
        List<AlarmConfigure> list = alarmConfigureService.selectAlarmConfigureList(alarmConfigure);
        return AjaxResult.success(list);
    }

    /**
     * 导出报警配置列表
     */
    @PreAuthorize(hasPermi = "alarm_configure:configure:export")
    @Log(title = "报警配置", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AlarmConfigure alarmConfigure) throws IOException
    {
        List<AlarmConfigure> list = alarmConfigureService.selectAlarmConfigureList(alarmConfigure);
        ExcelUtil<AlarmConfigure> util = new ExcelUtil<AlarmConfigure>(AlarmConfigure.class);
        util.exportExcel(response, list, "configure");
    }

    /**
     * 获取报警配置详细信息
     */
    @PreAuthorize(hasPermi = "alarm_configure:configure:query")
    @GetMapping(value = "/{alarmConfigureId}")
    public AjaxResult getInfo(@PathVariable("alarmConfigureId") Long alarmConfigureId)
    {
        return AjaxResult.success(alarmConfigureService.selectAlarmConfigureById(alarmConfigureId));
    }

    /**
     * 新增报警配置
     */
//    @PreAuthorize(hasPermi = "alarm_configure:configure:add")
    @Log(title = "报警配置", businessType = BusinessType.INSERT)
    @PostMapping("/add")
    public AjaxResult add(@RequestBody AlarmConfigure alarmConfigure) throws ParseException {
        alarmConfigure.setCreateBy(SecurityUtils.getUsername());
        return AjaxResult.success(alarmConfigureService.insertAlarmConfigure(alarmConfigure));
    }



    /**
     * 修改报警配置
     */
//    @PreAuthorize(hasPermi = "alarm_configure:configure:edit")
    @Log(title = "报警配置", businessType = BusinessType.UPDATE)
    @PutMapping("/update")
    public AjaxResult edit(@RequestBody AlarmConfigure alarmConfigure) throws ParseException {
        alarmConfigure.setUpdateBy(SecurityUtils.getUsername());
        return AjaxResult.success(alarmConfigureService.updateAlarmConfigure(alarmConfigure));
    }

    /**
     * 删除报警配置
     */
    @PreAuthorize(hasPermi = "alarm_configure:configure:remove")
    @Log(title = "报警配置", businessType = BusinessType.DELETE)
	@DeleteMapping("/delete/{alarmConfigureIds}")
    public AjaxResult remove(@PathVariable Long[] alarmConfigureIds)
    {
        return toAjax(alarmConfigureService.deleteAlarmConfigureByIds(alarmConfigureIds));
    }
}