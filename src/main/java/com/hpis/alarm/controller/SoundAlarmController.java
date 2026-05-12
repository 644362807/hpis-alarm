//package com.hpis.alarm.controller;
//
//import com.hpis.alarm.domain.SoundAlarm;
//import com.hpis.alarm.service.ISoundAlarmService;
//import com.hpis.common.core.utils.SecurityUtils;
//import com.hpis.common.core.utils.poi.ExcelUtil;
//import com.hpis.common.core.web.controller.BaseController;
//import com.hpis.common.core.web.domain.AjaxResult;
//import com.hpis.common.core.web.page.TableDataInfo;
//import com.hpis.common.log.annotation.Log;
//import com.hpis.common.log.enums.BusinessType;
//import com.hpis.common.security.annotation.PreAuthorize;
//import com.hpis.system.api.RemoteCustomerService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.*;
//
//import javax.servlet.http.HttpServletResponse;
//import java.io.IOException;
//import java.util.List;
//import java.util.Objects;
//
///**
// * 声音报警器管理Controller
// *
// * @author pc
// * @date 2023-08-18
// */
//@RestController
//@RequestMapping("/sound_alarm")
//public class SoundAlarmController extends BaseController {
//    @Autowired
//    private ISoundAlarmService soundAlarmService;
//
//    @Autowired
//    private RemoteCustomerService remoteCustomerService;
//
//    /**
//     * 查询声音报警器管理列表
//     */
//    @PreAuthorize(hasPermi = "alarm:sound_alarm:list")
//    @GetMapping("/list")
//    public TableDataInfo list(SoundAlarm soundAlarm) {
//        startPage();
//        List<SoundAlarm> list = soundAlarmService.selectSoundAlarmList(soundAlarm);
//        return getDataTable(list);
//    }
//
//    /**
//     * 导出声音报警器管理列表
//     */
//    @PreAuthorize(hasPermi = "alarm:sound_alarm:export")
//    @Log(title = "声音报警器管理", businessType = BusinessType.EXPORT)
//    @PostMapping("/export")
//    public void export(HttpServletResponse response, SoundAlarm soundAlarm) throws IOException {
//        List<SoundAlarm> list = soundAlarmService.selectSoundAlarmList(soundAlarm);
//        ExcelUtil<SoundAlarm> util = new ExcelUtil<SoundAlarm>(SoundAlarm.class);
//        util.exportExcel(response, list, "sound_alarm");
//    }
//
//    /**
//     * 获取声音报警器管理详细信息
//     */
//    @PreAuthorize(hasPermi = "alarm:sound_alarm:query")
//    @GetMapping(value = "/{soundId}")
//    public AjaxResult getInfo(@PathVariable("soundId") Long soundId) {
//        return AjaxResult.success(soundAlarmService.selectSoundAlarmById(soundId));
//    }
//
//    /**
//     * 新增声音报警器管理
//     */
//    @PreAuthorize(hasPermi = "alarm:sound_alarm:add")
//    @Log(title = "声音报警器管理", businessType = BusinessType.INSERT)
//    @PostMapping
//    public AjaxResult add(@RequestBody SoundAlarm soundAlarm) {
//        soundAlarm.setCreateBy(SecurityUtils.getUsername());
//        if (soundAlarmService.selectSoundAlarmByLocation(soundAlarm) != null) {
//
//            return AjaxResult.error("报警器位置名称重复");
//        }
//        return toAjax(soundAlarmService.insertSoundAlarm(soundAlarm));
//    }
//
//    /**
//     * 修改声音报警器管理
//     */
//    @PreAuthorize(hasPermi = "alarm:sound_alarm:edit")
//    @Log(title = "声音报警器管理", businessType = BusinessType.UPDATE)
//    @PutMapping
//    public AjaxResult edit(@RequestBody SoundAlarm soundAlarm) {
//        soundAlarm.setUpdateBy(SecurityUtils.getUsername());
//        SoundAlarm soundAlarmSelect = soundAlarmService.selectSoundAlarmByLocation(soundAlarm);
//        if (soundAlarmSelect != null && !Objects.equals(soundAlarmSelect.getSoundId(), soundAlarm.getSoundId())) {
//            return AjaxResult.error("报警器位置名称重复");
//        }
//        return toAjax(soundAlarmService.updateSoundAlarm(soundAlarm));
//    }
//
//    /**
//     * 删除声音报警器管理
//     */
//    @PreAuthorize(hasPermi = "alarm:sound_alarm:remove")
//    @Log(title = "声音报警器管理", businessType = BusinessType.DELETE)
//    @DeleteMapping("/{soundIds}")
//    public AjaxResult remove(@PathVariable Long[] soundIds) {
//        return toAjax(soundAlarmService.deleteSoundAlarmByIds(soundIds));
//    }
//}
