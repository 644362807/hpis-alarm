package com.hpis.alarm.controller;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.api.dto.AlarmElectrolyticCellDTO;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.dto.AlarmQueryParameter;
import com.hpis.alarm.service.IAlarmService;
import com.hpis.common.core.domain.R;
import com.hpis.common.core.web.controller.BaseController;
import com.hpis.common.core.web.domain.AjaxResult;
import com.hpis.common.core.web.page.TableDataInfo;
import com.hpis.common.log.annotation.Log;
import com.hpis.common.log.enums.BusinessType;
import com.hpis.common.security.annotation.PreAuthorize;
import com.hpis.common.security.service.TokenService;
import com.hpis.system.api.model.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 【请填写功能名称】Controller
 *
 * @author ruoyi
 * @date 2023-03-21
 */
@Slf4j
@RestController
@RequestMapping("/alarm")
public class AlarmController extends BaseController
{
    @Autowired
    private IAlarmService alarmService;


    /**
     * 查询报警记录列表
     */
//  @PreAuthorize(hasPermi = "alarm:alarm:list")
    @GetMapping("/list")
    public TableDataInfo list(Alarm alarm)
    {
        Page<Alarm> list = alarmService.selectAlarmPage(alarm);
        return getDataTable(list);
    }



    @GetMapping("/countAlarm")
    public AjaxResult selectAlarmPageTOP(Alarm alarm)
    {
        return AjaxResult.success(alarmService.countAlarm(alarm));
    }

    /**
     * 根据时间段获取温度报警次数统计
     */
    @PreAuthorize(hasPermi = "alarm:alarm:list")
    @GetMapping("/count/list")
    public AjaxResult getDeviceAlarmCountByDeviceIdAndDateRange(@RequestParam String deviceId,
                                                                   @RequestParam String dateRange,
                                                                   @RequestParam String customerId)
    {
        List<Map<String, Object>> data = alarmService.getDeviceAlarmCountByDeviceIdAndDateRange(deviceId, dateRange, customerId);
        return AjaxResult.success(data);
    }

    @GetMapping("/getPictureByPath")
    public AjaxResult getPictureByPath(Alarm alarm)
    {
        return AjaxResult.success(alarmService.getPictureByPath(alarm));
    }
    /**
     * 导出【请填写功能名称】列表
     */
    @PreAuthorize(hasPermi = "alarm:alarm:export")
    @Log(title = "【请填写功能名称】", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, Alarm alarm) throws IOException
    {
////        List<Alarm> list = alarmService.selectAlarmList(alarm);
//        ExcelUtil<Alarm> util = new ExcelUtil<Alarm>(Alarm.class);
//        util.exportExcel(response, list, "alarm");
    }

    /**
     * 获取报警图片
     */
    @GetMapping(value = "getAlarmPicture/{alarmId}")
    public AjaxResult getAlarmPicture(@PathVariable("alarmId") Long alarmId)
    {
        Alarm alarm = alarmService.getAlarmPicture(alarmId);
        return AjaxResult.success(alarm);
    }

    @GetMapping(value = "/alarmTimeCountByMonth")
    public AjaxResult alarmTimeCountByMonth(  AlarmQueryParameter alarmQueryParameter){
        return AjaxResult.success(alarmService.alarmTimeCountByMonth(alarmQueryParameter));
    }


    /**
     * 获取报警详细信息
     */
    @PreAuthorize(hasPermi = "alarm:alarm:query")
    @GetMapping(value = "query/{alarmId}")
    public AjaxResult getInfo(@PathVariable("alarmId") Long alarmId)
    {
        Alarm alarm = alarmService.selectAlarmById(alarmId);
        return AjaxResult.success(alarm);
    }



    /**
     * 注册报警信息
     */
    @PostMapping("/alarmAdd")
    public void alarmStart(@RequestBody JSONObject object) throws IOException {
        //报警开始推送
        try {
            alarmService.insertAlarm(object);
//            alarmService.alarmStop(object);
        }catch (Exception e){
            log.error("报警推送异常:{}",e.getMessage());
            e.printStackTrace();
        }
    }




    /**
     * 处理报警信息
     */
    @PreAuthorize(hasPermi = "alarm:alarm:edit")
    @Log(title = "处理报警信息", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody Alarm alarm)
    {
        return toAjax(alarmService.updateAlarm(alarm));
    }

    /**
     * 删除报警信息
     */
    @PreAuthorize(hasPermi = "alarm:alarm:remove")
    @Log(title = "删除报警信息", businessType = BusinessType.DELETE)
	@DeleteMapping("/{alarmIds}")
    public AjaxResult remove(@PathVariable Long[] alarmIds)
    {
        return toAjax(alarmService.deleteAlarmByIds(alarmIds));
    }

    /**
     * 根据用户 行业 时间 统计报警类型
     * @param alarmQueryParameter
     * @return
     */
    @GetMapping("/alarmModeCount")
    public AjaxResult alarmModeCount(AlarmQueryParameter alarmQueryParameter)
    {
        return AjaxResult.success( alarmService.alarmModeCount(alarmQueryParameter));
    }


    /**
     * 查询电解槽关联报警列表
     *
     * @param json 电解槽关联报警
     * @return 电解槽关联报警集合
     */
    @PostMapping(value = "/alarmStopByIrmsSn")
    public R alarmStopByIrmsSn(@RequestBody JSONObject json) {
        return R.ok(alarmService.alarmStopByIrmsSn(json));
    }

    /**
     * 根据用户 行业 时间 统计报警类型
     * @param alarmQueryParameter
     * @return
     */
    @GetMapping("/alarmCountByTime")
    public AjaxResult alarmCountByTime(AlarmQueryParameter alarmQueryParameter)
    {
        return AjaxResult.success( alarmService.alarmCountByTime(alarmQueryParameter));
    }

    /**
     * 根据用户 行业 时间 统计报警类型
     * @param alarmQueryParameter
     * @return
     */
    @GetMapping("/AlarmOfDay")
    public AjaxResult AlarmOfDay(AlarmQueryParameter alarmQueryParameter)
    {
        return AjaxResult.success( alarmService.AlarmOfDay(alarmQueryParameter));
    }


    @PostMapping("/alarmStopByDeviceSn")
    public AjaxResult alarmStopByDeviceSn(@RequestBody JSONObject alarmQueryParameter)
    {
        alarmService.alarmStopByDeviceSn(alarmQueryParameter);
        return AjaxResult.success("fk");
    }

}
