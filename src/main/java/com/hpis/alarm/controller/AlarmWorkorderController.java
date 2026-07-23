package com.hpis.alarm.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmWorkorder;
import com.hpis.alarm.dto.WorkorderCloseRequest;
import com.hpis.alarm.dto.WorkorderCompleteRequest;
import com.hpis.alarm.service.IAlarmWorkorderService;
import com.hpis.common.core.web.controller.BaseController;
import com.hpis.common.core.web.domain.AjaxResult;
import com.hpis.common.core.web.page.TableDataInfo;
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
    public TableDataInfo list(AlarmWorkorder alarmWorkorder) {
        Page<AlarmWorkorder> page = alarmWorkorderService.selectAlarmWorkorderPage(alarmWorkorder);
        return getDataTable(page);
    }

    @PreAuthorize(hasPermi = "alarm:workorder:list")
    @GetMapping("/my")
    public TableDataInfo my(AlarmWorkorder alarmWorkorder) {
        Page<AlarmWorkorder> page = alarmWorkorderService.selectMyAlarmWorkorderPage(alarmWorkorder);
        return getDataTable(page);
    }

    @PreAuthorize(hasPermi = "alarm:workorder:query")
    @GetMapping("/my/{workorderId}")
    public AjaxResult getMyInfo(@PathVariable("workorderId") Long workorderId) {
        return AjaxResult.success(alarmWorkorderService.selectMyAlarmWorkorderById(workorderId));
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
    public AjaxResult complete(@RequestBody WorkorderCompleteRequest request) {
        return toAjax(alarmWorkorderService.completeWorkorder(toCommand(request)));
    }

    @PreAuthorize(hasPermi = "alarm:workorder:close")
    @Log(title = "报警工单异常关闭", businessType = BusinessType.UPDATE)
    @PutMapping("/close")
    public AjaxResult close(@RequestBody WorkorderCloseRequest request) {
        return toAjax(alarmWorkorderService.closeWorkorder(toCommand(request)));
    }

    @PreAuthorize(hasPermi = "alarm:workorder:remove")
    @Log(title = "报警工单", businessType = BusinessType.DELETE)
    @DeleteMapping("/{workorderIds}")
    public AjaxResult remove(@PathVariable Long[] workorderIds) {
        return toAjax(alarmWorkorderService.deleteAlarmWorkorderByIds(workorderIds));
    }

    private AlarmWorkorder toCommand(WorkorderCompleteRequest request) {
        AlarmWorkorder command = new AlarmWorkorder();
        if (request != null) {
            command.setWorkorderId(request.getWorkorderId());
            command.setHandleResult(request.getHandleResult());
            command.setHandlePicture(request.getHandlePicture());
        }
        return command;
    }

    private AlarmWorkorder toCommand(WorkorderCloseRequest request) {
        AlarmWorkorder command = new AlarmWorkorder();
        if (request != null) {
            command.setWorkorderId(request.getWorkorderId());
            command.setHandleResult(request.getHandleResult());
            command.setHandlePicture(request.getHandlePicture());
        }
        return command;
    }
}
