//package com.hpis.alarm.controller;
//
//import com.hpis.alarm.domain.AlarmSendLog;
//import com.hpis.alarm.service.IAlarmSendLogService;
//import com.hpis.common.core.utils.SecurityUtils;
//import com.hpis.common.core.utils.poi.ExcelUtil;
//import com.hpis.common.core.web.controller.BaseController;
//import com.hpis.common.core.web.domain.AjaxResult;
//import com.hpis.common.core.web.page.TableDataInfo;
//import com.hpis.common.log.annotation.Log;
//import com.hpis.common.log.enums.BusinessType;
//import com.hpis.common.security.annotation.PreAuthorize;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.*;
//
//import javax.servlet.http.HttpServletResponse;
//import java.io.IOException;
//import java.util.List;
//
///**
// * 推送记录Controller
// *
// * @author pc
// * @date 2023-08-09
// */
//@RestController
//@RequestMapping("/log")
//public class AlarmSendLogController extends BaseController
//{
//    @Autowired
//    private IAlarmSendLogService alarmSendLogService;
//
//    /**
//     * 查询推送记录列表
//     */
//    @PreAuthorize(hasPermi = "alarm:log:list")
//    @GetMapping("/list")
//    public TableDataInfo list(AlarmSendLog alarmSendLog)
//    {
//        startPage() ;
//        List<AlarmSendLog> list = alarmSendLogService.selectAlarmSendLogList(alarmSendLog);
//        return getDataTable(list);
//    }
//
//    /**
//     * 导出推送记录列表
//     */
//    @PreAuthorize(hasPermi = "alarm:log:export")
//    @Log(title = "推送记录", businessType = BusinessType.EXPORT)
//    @PostMapping("/export")
//    public void export(HttpServletResponse response, AlarmSendLog alarmSendLog) throws IOException
//    {
//        List<AlarmSendLog> list = alarmSendLogService.selectAlarmSendLogList(alarmSendLog);
//        ExcelUtil<AlarmSendLog> util = new ExcelUtil<AlarmSendLog>(AlarmSendLog.class);
//        util.exportExcel(response, list, "log");
//    }
//
//    /**
//     * 获取推送记录详细信息
//     */
//    @PreAuthorize(hasPermi = "alarm:log:query")
//    @GetMapping(value = "/{sendLogId}")
//    public AjaxResult getInfo(@PathVariable("sendLogId") Long sendLogId)
//    {
//        return AjaxResult.success(alarmSendLogService.selectAlarmSendLogById(sendLogId));
//    }
//
//    /**
//     * 新增推送记录
//     */
//    @PreAuthorize(hasPermi = "alarm:log:add")
//    @Log(title = "推送记录", businessType = BusinessType.INSERT)
//    @PostMapping
//    public AjaxResult add(@RequestBody AlarmSendLog alarmSendLog)
//    {
//        alarmSendLog.setCreateBy(SecurityUtils.getUsername());
//        return toAjax(alarmSendLogService.insertAlarmSendLog(alarmSendLog));
//    }
//
//    /**
//     * 修改推送记录
//     */
//    @PreAuthorize(hasPermi = "alarm:log:edit")
//    @Log(title = "推送记录", businessType = BusinessType.UPDATE)
//    @PutMapping
//    public AjaxResult edit(@RequestBody AlarmSendLog alarmSendLog)
//    {
//        alarmSendLog.setUpdateBy(SecurityUtils.getUsername());
//        return toAjax(alarmSendLogService.updateAlarmSendLog(alarmSendLog));
//    }
//
//    /**
//     * 删除推送记录
//     */
//    @PreAuthorize(hasPermi = "alarm:log:remove")
//    @Log(title = "推送记录", businessType = BusinessType.DELETE)
//	@DeleteMapping("/{sendLogIds}")
//    public AjaxResult remove(@PathVariable Long[] sendLogIds)
//    {
//        return toAjax(alarmSendLogService.deleteAlarmSendLogByIds(sendLogIds));
//    }
//
//    /**
//     * 重新推送
//     */
//    @PreAuthorize(hasPermi = "alarm:log:edit")
//    @Log(title = "重新发送", businessType = BusinessType.UPDATE)
//	@PutMapping("/resend")
//    public AjaxResult resend(@RequestBody Long[] sendLogIds)
//    {
//        return toAjax(alarmSendLogService.resendAlarmSendByIds(sendLogIds));
//    }
//}
