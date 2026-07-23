package com.hpis.alarm.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmWorkorder;

import java.util.List;

/**
 * 报警工单服务。
 */
public interface IAlarmWorkorderService extends IService<AlarmWorkorder> {

    AlarmWorkorder selectAlarmWorkorderById(Long workorderId);

    AlarmWorkorder selectAlarmWorkorderByAlarmId(Long alarmId);

    AlarmWorkorder selectMyAlarmWorkorderById(Long workorderId);

    Page<AlarmWorkorder> selectAlarmWorkorderPage(AlarmWorkorder alarmWorkorder);

    Page<AlarmWorkorder> selectMyAlarmWorkorderPage(AlarmWorkorder alarmWorkorder);

    List<AlarmWorkorder> selectAlarmWorkorderList(AlarmWorkorder alarmWorkorder);

    int createWorkorder(AlarmWorkorder alarmWorkorder);

    int updateWorkorder(AlarmWorkorder alarmWorkorder);

    int transferWorkorder(AlarmWorkorder alarmWorkorder);

    int completeWorkorder(AlarmWorkorder alarmWorkorder);

    int closeWorkorder(AlarmWorkorder alarmWorkorder);

    int deleteAlarmWorkorderByIds(Long[] workorderIds);
}
