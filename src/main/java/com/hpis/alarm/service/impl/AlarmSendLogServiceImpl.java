package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.domain.AlarmSendLog;
import com.hpis.alarm.domain.MailDTO;
import com.hpis.alarm.enums.SendCategoryEnums;
import com.hpis.alarm.mapper.AlarmSendLogMapper;
import com.hpis.alarm.service.IAlarmSendLogService;
import com.hpis.alarm.service.MailService;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.shortMessage.SendShortMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 推送记录Service业务层处理
 *
 * @author pc
 * @date 2023-08-09
 */
@Service
public class AlarmSendLogServiceImpl implements IAlarmSendLogService {
    @Autowired
    private AlarmSendLogMapper alarmSendLogMapper;

    @Autowired
    private AlarmSendServiceImpl alarmSendService;

    @Autowired
    MailService mailService;

    @Autowired
    SendShortMessage sendShortMessage;

    /**
     * 查询推送记录
     *
     * @param sendLogId 推送记录ID
     * @return 推送记录
     */
    @Override
    public AlarmSendLog selectAlarmSendLogById(Long sendLogId) {
        return alarmSendLogMapper.selectAlarmSendLogById(sendLogId);
    }

    /**
     * 查询推送记录列表
     *
     * @param alarmSendLog 推送记录
     * @return 推送记录
     */
    @Override
    public List<AlarmSendLog> selectAlarmSendLogList(AlarmSendLog alarmSendLog) {
        return alarmSendLogMapper.selectAlarmSendLogList(alarmSendLog);
    }

    /**
     * 新增推送记录
     *
     * @param alarmSendLog 推送记录
     * @return 结果
     */
    @Override
    public int insertAlarmSendLog(AlarmSendLog alarmSendLog) {
        alarmSendLog.setCreateTime(DateUtils.getNowDate());
        return alarmSendLogMapper.insertAlarmSendLog(alarmSendLog);
    }

    /**
     * 修改推送记录
     *
     * @param alarmSendLog 推送记录
     * @return 结果
     */
    @Override
    public int updateAlarmSendLog(AlarmSendLog alarmSendLog) {
        alarmSendLog.setUpdateTime(DateUtils.getNowDate());
        return alarmSendLogMapper.updateAlarmSendLog(alarmSendLog);
    }

    /**
     * 批量删除推送记录
     *
     * @param sendLogIds 需要删除的推送记录ID
     * @return 结果
     */
    @Override
    public int deleteAlarmSendLogByIds(Long[] sendLogIds) {
        return alarmSendLogMapper.deleteAlarmSendLogByIds(sendLogIds);
    }

    /**
     * 删除推送记录信息
     *
     * @param sendLogId 推送记录ID
     * @return 结果
     */
    @Override
    public int deleteAlarmSendLogById(Long sendLogId) {
        return alarmSendLogMapper.deleteAlarmSendLogById(sendLogId);
    }

    @Override
    public int resendAlarmSendByIds(Long[] sendLogIds) {
        String sendStatus;
        long allStatus = 1;
        for (Long id : sendLogIds) {
            AlarmSendLog alarmSendLog = alarmSendLogMapper.selectAlarmSendLogById(id);
            if (alarmSendLog.getSendMethod().equals(SendCategoryEnums.SEND_BY_EMAIL.getKey())) {
                sendStatus = "0";
                MailDTO mailDTO = new MailDTO();
                mailDTO.setTo(new String[]{alarmSendLog.getSendTarget()});
                mailDTO.setSubject("【设备报警】");
                mailDTO.setText(alarmSendLog.getSendContent());
                mailDTO.setSentDate(new Date());
                boolean status = mailService.sendSimpleMailMessage(mailDTO);
                if (status) {
                    sendStatus = "1";
                } else {
                    allStatus = 0;
                }
                alarmSendLog.setSendStatus(sendStatus);
                alarmSendLog.setSendTime(null);
                alarmSendLog.setUpdateTime(new Date());
                alarmSendLog.setUpdateBy(SecurityUtils.getUsername());
                alarmSendLogMapper.updateAlarmSendLog(alarmSendLog);
            } else if (alarmSendLog.getSendMethod().equals(SendCategoryEnums.SEND_BY_SMS.getKey())) {
                sendStatus = "0";
                JSONObject re;
                re = sendShortMessage.sendAlarmShortMessage
                        (alarmSendLog.getSendTarget(), alarmSendLog.getSendContent());
                if (re.get("message").equals("success")) {
                    sendStatus = "1";
                } else {
                    allStatus = 0;
                }
                alarmSendLog.setSendStatus(sendStatus);
                alarmSendLog.setSendTime(null);
                alarmSendLog.setUpdateTime(new Date());
                alarmSendLog.setUpdateBy(SecurityUtils.getUsername());
                alarmSendLogMapper.updateAlarmSendLog(alarmSendLog);
            }
        }
        return allStatus == 1 ? 1 : 0;
    }
}
