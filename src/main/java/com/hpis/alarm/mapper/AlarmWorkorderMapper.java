package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.AlarmWorkorder;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 报警工单 Mapper。
 */
public interface AlarmWorkorderMapper extends BaseMapper<AlarmWorkorder> {

    AlarmWorkorder selectAlarmWorkorderByIdAndTenant(@Param("workorderId") Long workorderId,
                                                       @Param("tenantId") Long tenantId);

    AlarmWorkorder selectAlarmWorkorderByAlarmIdAndTenant(@Param("alarmId") Long alarmId,
                                                           @Param("tenantId") Long tenantId);

    AlarmWorkorder selectMyAlarmWorkorderById(@Param("workorderId") Long workorderId,
                                               @Param("tenantId") Long tenantId,
                                               @Param("assigneeId") Long assigneeId);

    Page<AlarmWorkorder> selectAlarmWorkorderPage(Page<AlarmWorkorder> page,
                                                   @Param("query") AlarmWorkorder query,
                                                   @Param("tenantId") Long tenantId);

    Page<AlarmWorkorder> selectMyAlarmWorkorderPage(Page<AlarmWorkorder> page,
                                                     @Param("query") AlarmWorkorder query,
                                                     @Param("tenantId") Long tenantId,
                                                     @Param("assigneeId") Long assigneeId);

    List<AlarmWorkorder> selectAlarmWorkorderList(AlarmWorkorder alarmWorkorder);

    int insertAlarmWorkorder(AlarmWorkorder alarmWorkorder);

    int updateEditableByIdAndTenant(@Param("workorder") AlarmWorkorder workorder,
                                    @Param("tenantId") Long tenantId);

    int updateAssigneeByIdAndTenant(@Param("workorder") AlarmWorkorder workorder,
                                    @Param("tenantId") Long tenantId);

    int completeByIdAndOwner(@Param("workorderId") Long workorderId,
                             @Param("tenantId") Long tenantId,
                             @Param("assigneeId") Long assigneeId,
                             @Param("handleResult") String handleResult,
                             @Param("updateBy") String updateBy,
                             @Param("updateTime") java.util.Date updateTime);

    int closeByIdAndTenant(@Param("workorderId") Long workorderId,
                           @Param("tenantId") Long tenantId,
                           @Param("handleResult") String handleResult,
                           @Param("updateBy") String updateBy,
                           @Param("updateTime") java.util.Date updateTime);

    int deleteByIdsAndTenant(@Param("workorderIds") Long[] workorderIds,
                             @Param("tenantId") Long tenantId,
                             @Param("updateBy") String updateBy,
                             @Param("updateTime") java.util.Date updateTime);
}
