package com.hpis.alarm.controller;

import com.hpis.alarm.domain.AlarmWorkorder;
import com.hpis.alarm.service.IAlarmWorkorderService;
import com.hpis.common.core.web.controller.BaseController;
import com.hpis.common.core.web.domain.AjaxResult;
import com.hpis.common.log.annotation.Log;
import com.hpis.common.log.enums.BusinessType;
import com.hpis.common.security.annotation.PreAuthorize;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报警工单 Controller。
 */
@RestController
@RequestMapping("/workorder")
public class AlarmWorkorderController extends BaseController {

    @Autowired
    private IAlarmWorkorderService alarmWorkorderService;

    @PreAuthorize(hasPermi = "alarm:workorder:list")
    @GetMapping("/list")
    public AjaxResult list(AlarmWorkorder alarmWorkorder) {
        List<AlarmWorkorder> list = alarmWorkorderService.selectAlarmWorkorderList(alarmWorkorder);
        return AjaxResult.success(list);
    }

    @PreAuthorize(hasPermi = "alarm:workorder:query")
    @GetMapping("/{workorderId}")
    public AjaxResult getInfo(@PathVariable("workorderId") Long workorderId) {
        return AjaxResult.success(alarmWorkorderService.selectAlarmWorkorderById(workorderId));
    }

    @PreAuthorize(hasPermi = "alarm:workorder:add")
    @Log(title = "报警工单", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AlarmWorkorder alarmWorkorder) {
        return toAjax(alarmWorkorderService.createWorkorder(alarmWorkorder));
    }

    @PreAuthorize(hasPermi = "alarm:workorder:edit")
    @Log(title = "报警工单", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AlarmWorkorder alarmWorkorder) {
        return toAjax(alarmWorkorderService.updateWorkorder(alarmWorkorder));
    }

    @PreAuthorize(hasPermi = "alarm:workorder:transfer")
    @Log(title = "报警工单转派", businessType = BusinessType.UPDATE)
    @PutMapping("/transfer")
    public AjaxResult transfer(@RequestBody AlarmWorkorder alarmWorkorder) {
        return toAjax(alarmWorkorderService.transferWorkorder(alarmWorkorder));
    }

    @PreAuthorize(hasPermi = "alarm:workorder:complete")
    @Log(title = "报警工单完成", businessType = BusinessType.UPDATE)
    @PutMapping("/complete")
    public AjaxResult complete(@RequestBody AlarmWorkorder alarmWorkorder) {
        return toAjax(alarmWorkorderService.completeWorkorder(alarmWorkorder));
    }

    @PreAuthorize(hasPermi = "alarm:workorder:remove")
    @Log(title = "报警工单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{workorderIds}")
    public AjaxResult remove(@PathVariable Long[] workorderIds) {
        return toAjax(alarmWorkorderService.deleteAlarmWorkorderByIds(workorderIds));
    }
}
