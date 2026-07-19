package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpis.alarm.domain.AlarmWorkorder;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 报警工单 Mapper。
 */
public interface AlarmWorkorderMapper extends BaseMapper<AlarmWorkorder> {

    AlarmWorkorder selectAlarmWorkorderById(Long workorderId);

    AlarmWorkorder selectAlarmWorkorderByIdAndTenant(@Param("workorderId") Long workorderId,
                                                       @Param("tenantId") Long tenantId);

    AlarmWorkorder selectAlarmWorkorderByAlarmId(Long alarmId);

    List<AlarmWorkorder> selectAlarmWorkorderList(AlarmWorkorder alarmWorkorder);

    int insertAlarmWorkorder(AlarmWorkorder alarmWorkorder);

    int updateAlarmWorkorder(AlarmWorkorder alarmWorkorder);

    int updateAssigneeByIdAndTenant(@Param("workorder") AlarmWorkorder workorder,
                                    @Param("tenantId") Long tenantId);

    int deleteAlarmWorkorderByIds(Long[] workorderIds);
}
