//package com.hpis.alarm.controller;
//
//import com.alibaba.fastjson.JSONArray;
//import com.hpis.alarm.domain.AlarmSend;
//import com.hpis.alarm.service.IAlarmSendService;
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
//import java.util.HashMap;
//import java.util.List;
//
///**
// * 推送Controller
// *
// * @author pc
// * @date 2023-08-03
// */
//@RestController
//@RequestMapping("/send")
//public class AlarmSendController extends BaseController {
//
//    @Autowired
//    private IAlarmSendService alarmSendService;
//
//
//    /**
//     * 获取报警配置详细信息
//     */
//    @PreAuthorize(hasPermi = "alarm:configure:query")
//    @GetMapping(value = "/device/{deviceId}")
//    public AjaxResult getAlarmSendByDeviceId(@PathVariable("deviceId") Long deviceId) {
//        return AjaxResult.success(alarmSendService.selectAlarmConfigureByDeviceId(deviceId));
//    }
//
//    /**
//     * 报警推送配置的保存或更新
//     */
//    @PreAuthorize(hasPermi = "alarm:send:add")
//    @Log(title = "保存推送消息设置", businessType = BusinessType.INSERT)
//    @PostMapping(value = "/add")
//    public AjaxResult addConfig(@RequestBody JSONArray jsonArray) {
//        HashMap<String, String> idMap = alarmSendService.saveAlarmSend(jsonArray);
//        return AjaxResult.success(idMap);
//    }
//
//
//
//
//
//    /**
//     * 查询推送列表
//     */
////    @PreAuthorize(hasPermi = "alarm:send:list")
//    @GetMapping("/list")
//    public TableDataInfo list(AlarmSend alarmSend) {
//        startPage();
//        List<AlarmSend> list = alarmSendService.selectAlarmSendList(alarmSend);
//        return getDataTable(list);
//    }
//
//    /**
//     * 导出推送列表
//     */
////    @PreAuthorize(hasPermi = "alarm:send:export")
//    @Log(title = "推送", businessType = BusinessType.EXPORT)
//    @PostMapping("/export")
//    public void export(HttpServletResponse response, AlarmSend alarmSend) throws IOException {
//        List<AlarmSend> list = alarmSendService.selectAlarmSendList(alarmSend);
//        ExcelUtil<AlarmSend> util = new ExcelUtil<AlarmSend>(AlarmSend.class);
//        util.exportExcel(response, list, "send");
//    }
//
//    /**
//     * 获取推送详细信息
//     */
////    @PreAuthorize(hasPermi = "alarm:send:query")
//    @GetMapping(value = "/{alarmSendId}")
//    public AjaxResult getInfo(@PathVariable("alarmSendId") Long alarmSendId) {
//        return AjaxResult.success(alarmSendService.selectAlarmSendById(alarmSendId));
//    }
//
//    /**
//     * 新增推送
//     */
////    @PreAuthorize(hasPermi = "alarm:send:add")
//    @Log(title = "推送", businessType = BusinessType.INSERT)
//    @PostMapping
//    public AjaxResult add(@RequestBody AlarmSend alarmSend) {
//        alarmSend.setCreateBy(SecurityUtils.getUsername());
//        return toAjax(alarmSendService.insertAlarmSend(alarmSend));
//    }
//
//    /**
//     * 修改推送
//     */
////    @PreAuthorize(hasPermi = "alarm:send:edit")
//    @Log(title = "推送", businessType = BusinessType.UPDATE)
//    @PutMapping
//    public AjaxResult edit(@RequestBody AlarmSend alarmSend) {
//        alarmSend.setUpdateBy(SecurityUtils.getUsername());
//        return toAjax(alarmSendService.updateAlarmSend(alarmSend));
//    }
//
//    /**
//     * 删除推送
//     */
////    @PreAuthorize(hasPermi = "alarm:send:remove")
//    @Log(title = "推送", businessType = BusinessType.DELETE)
//    @DeleteMapping("/{alarmSendIds}")
//    public AjaxResult remove(@PathVariable Long[] alarmSendIds) {
//        return toAjax(alarmSendService.deleteAlarmSendByIds(alarmSendIds));
//    }
//}
