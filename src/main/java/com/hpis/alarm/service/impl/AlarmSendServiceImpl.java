package com.hpis.alarm.service.impl;

import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hpis.alarm.domain.AlarmSend;
import com.hpis.alarm.domain.AlarmSendLog;
import com.hpis.alarm.domain.MailDTO;
import com.hpis.alarm.enums.SendCategoryEnums;
import com.hpis.alarm.mapper.AlarmSendMapper;
import com.hpis.alarm.service.IAlarmSendService;
import com.hpis.alarm.service.MailService;
import com.hpis.common.core.constant.CacheConstants;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.core.utils.shortMessage.SendShortMessage;
import com.hpis.common.core.utils.wechat.WechatAlarmData;
import com.hpis.common.core.utils.wechat.WechatConfigInfo;
import com.hpis.common.core.utils.wechat.WechatSendMessage;
import com.hpis.common.core.web.domain.AjaxResult;
import com.hpis.common.redis.service.RedisService;
import com.hpis.system.api.RemoteCustomerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 推送Service业务层处理
 *
 * @author pc
 * @date 2023-08-03
 */
@Service
@Slf4j
public class AlarmSendServiceImpl implements IAlarmSendService {

    @Autowired
    private AlarmSendMapper alarmSendMapper;

    @Autowired
    private AlarmSendLogServiceImpl alarmSendLogService;

    @Autowired
    private MailService mailService;

    @Autowired
    private SendShortMessage sendShortMessage;

    @Autowired
    private RedisService redisService;

    /**
     * service是单例的，并发下lock一个实例
     * 互斥锁，使用公平锁
     */
    private ReentrantLock reentrantLock = new ReentrantLock(true);

    @Autowired
    private WechatSendMessage wechatSendMessage;

    @Autowired
    private RemoteCustomerService remoteCustomerService;

    /**
     * 查询推送
     *
     * @param alarmSendId 推送ID
     * @return 推送
     */
    @Override
    public AlarmSend selectAlarmSendById(Long alarmSendId) {
        return alarmSendMapper.selectAlarmSendById(alarmSendId);
    }

    /**
     * 查询推送列表
     *
     * @param alarmSend 推送
     * @return 推送
     */
    @Override
    public List<AlarmSend> selectAlarmSendList(AlarmSend alarmSend) {
        return alarmSendMapper.selectAlarmSendList(alarmSend);
    }

    /**
     * 新增推送
     *
     * @param alarmSend 推送
     * @return 结果
     */
    @Override
    public int insertAlarmSend(AlarmSend alarmSend) {
        alarmSend.setCreateTime(DateUtils.getNowDate());
        return alarmSendMapper.insertAlarmSend(alarmSend);
    }

    /**
     * 修改推送
     *
     * @param alarmSend 推送
     * @return 结果
     */
    @Override
    public int updateAlarmSend(AlarmSend alarmSend) {
        alarmSend.setUpdateTime(DateUtils.getNowDate());
        return alarmSendMapper.updateAlarmSend(alarmSend);
    }

    /**
     * 批量删除推送
     *
     * @param alarmSendIds 需要删除的推送ID
     * @return 结果
     */
    @Override
    public int deleteAlarmSendByIds(Long[] alarmSendIds) {
        return alarmSendMapper.deleteAlarmSendByIds(alarmSendIds);
    }

    /**
     * 删除推送信息
     *
     * @param alarmSendId 推送ID
     * @return 结果
     */
    @Override
    public int deleteAlarmSendById(Long alarmSendId) {
        return alarmSendMapper.deleteAlarmSendById(alarmSendId);
    }

    @Override
    public List<AlarmSend> selectAlarmConfigureByDeviceId(Long deviceId) {
        return alarmSendMapper.selectAlarmConfigureByDeviceId(deviceId);
    }


    @Override
    public void sendRemote(Long deviceId, Long customerId, String type, WechatAlarmData wechatAlarmData) {
        /**
         * 优化方案1：开启多线程处理
         * 2：先查询出当前设备的所有已经开启的推送设置，并放入缓存
         * 3：筛选出对应的报警类型
         */
//        String settings = getSettingsByDevice(deviceId);
//        List<AlarmSend> alarmSends = JSONObject.parseArray(settings).toJavaList(AlarmSend.class);
        AlarmSend alarmSend = new AlarmSend();
        alarmSend.setDeviceId(deviceId);
        //已开启的
        alarmSend.setDeviceAlarmControl("1");
        List<AlarmSend> alarmSends = alarmSendMapper.selectAlarmSendList(alarmSend);
        alarmSends.parallelStream().filter(send -> send.getAlarmCategories().contains(type))
                .forEach(as -> processAlarm(as, as.getAlarmMethod(), customerId, wechatAlarmData));
    }

    /**
     * 查询设备关联报警推送设置
     * @param deviceId
     * @return
     */
    public String getSettingsByDevice(Long deviceId) {
        //查询缓存中的推送设置
        String settings = redisService.getCacheObject(deviceId + CacheConstants.ALARM_SEND_SETTING);
        if (StringUtils.isBlank(settings)) {
            if (reentrantLock.tryLock()) {
                AlarmSend alarmSend = new AlarmSend();
                alarmSend.setDeviceId(deviceId);
                //已开启的
                alarmSend.setDeviceAlarmControl("1");
                List<AlarmSend> alarmSends = alarmSendMapper.selectAlarmSendList(alarmSend);
                redisService.setCacheObject(deviceId + CacheConstants.ALARM_SEND_SETTING, JSONObject.toJSONString(alarmSends));
                //释放锁
                reentrantLock.unlock();
                settings = getSettingsByDevice(deviceId);
            } else {
                //获取锁失败，暂停100ms再去重新获取锁
                try {
                    Thread.sleep(100);
                    settings = getSettingsByDevice(deviceId);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return settings;
    }


    /**
     * 判断报警是否符合推送条件
     */
    public void processAlarm(AlarmSend alarmSend, String method, Long customerId, WechatAlarmData wechatAlarmData) {
        String alarmTargets = alarmSend.getAlarmTargets();
        String[] targets = alarmTargets.split(",");

        if (alarmSend.getIsAllDay().equals("1")) {
            sendMsg(method, targets, customerId, wechatAlarmData);
            return;
        }

        String jsonString = alarmSend.getAlarmTimePeriods();
        if ("[\"\"]".equals(jsonString)) {
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        List<List<String>> timePeriods;
        try {
            timePeriods = mapper.readValue(jsonString, List.class);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return;
        }
        LocalTime now = LocalTime.now();
        for (List<String> period : timePeriods) {
            LocalTime start = LocalTime.parse(period.get(0));
            LocalTime end = LocalTime.parse(period.get(1));
            if (now.isAfter(start) && now.isBefore(end)) {
                sendMsg(method, targets, customerId, wechatAlarmData);
            }
        }
    }

    /**
     * 发送消息
     * @param method
     * @param alarmTargets
     * @param customerId
     * @param wechatAlarmData 消息主体，根据需要选择
     */
    public void sendMsg(String method, String[] alarmTargets, Long customerId, WechatAlarmData wechatAlarmData) {
        String sendStatus;
        // msg根据需要修改
        StringBuilder msg = new StringBuilder();
        msg.append("\n设备报警推送");
        if (wechatAlarmData != null) {
            if (StringUtils.isNotBlank(wechatAlarmData.getDeviceName())) {
                msg.append("\n检测设备：" + wechatAlarmData.getDeviceName());
            }
            if (StringUtils.isNotBlank(wechatAlarmData.getTargetName())) {
                msg.append("\n观察位置：" + wechatAlarmData.getTargetName());
            }
            if (StringUtils.isNotBlank(wechatAlarmData.getAlarmType())) {
                msg.append("\n检测方式：" + wechatAlarmData.getAlarmType());
            }
            if (StringUtils.isNotBlank(wechatAlarmData.getAlarmRank())) {
                msg.append("\n事件级别：" + wechatAlarmData.getAlarmRank());
            }
            if (wechatAlarmData.getAlarmBegintime() != null) {
                Date alarmBeginTime = wechatAlarmData.getAlarmBegintime();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String formattedDate = sdf.format(alarmBeginTime);
                msg.append("\n事件开始时间：" + formattedDate);
            }
            if (StringUtils.isNotBlank(wechatAlarmData.getMaxTemp())) {
                msg.append("\n报警最高温：" + wechatAlarmData.getMaxTemp() + "℃");
            }
        }
        String msgStr = msg.toString();
        if (method.equals(SendCategoryEnums.SEND_BY_EMAIL.getKey())) {
            MailDTO mailDTO = new MailDTO();
            mailDTO.setTo(alarmTargets);
            mailDTO.setSubject("【设备报警推送】");
            mailDTO.setText(msgStr);
            mailDTO.setSentDate(new Date());
            boolean emailStatus = mailService.sendSimpleMailMessage(mailDTO);
            sendStatus = emailStatus ? "1" : "0";
//            for (String email : alarmTargets) {
//                logSendStatus(email, method, sendStatus, msgStr, customerId);
//            }
        } else if (method.equals(SendCategoryEnums.SEND_BY_SMS.getKey())) {
            for (String sms : alarmTargets) {
                sendStatus = "0";
                JSONObject jsonObject = sendShortMessage.sendAlarmShortMessage(sms, msgStr);
                if (jsonObject.get("message").equals("success")) {
                    sendStatus = "1";
                }
//                logSendStatus(sms, method, sendStatus, msgStr, customerId);
            }
        } else if (method.equals(SendCategoryEnums.SEND_BY_WECHAT.getKey())) {
            WechatConfigInfo wechatConfigInfo = redisService.getCacheObject(customerId + CacheConstants.WECHAT_CONFIG_INFO);
            //缓存无 去查数据库
            if (wechatConfigInfo == null) {
                AjaxResult ajaxResult = remoteCustomerService.getInfo(customerId);
                cn.hutool.json.JSONObject jsonObject = JSONUtil.parseObj(ajaxResult.get("data"));
                if (StringUtils.isNotBlank(jsonObject.getStr("appID")) && StringUtils.isNotBlank(jsonObject.getStr("appsecret"))
                        && StringUtils.isNotBlank(jsonObject.getStr("templateId"))) {
                    wechatConfigInfo = new WechatConfigInfo();
                    wechatConfigInfo.setAppID(jsonObject.getStr("appID"));
                    wechatConfigInfo.setAppsecret(jsonObject.getStr("appsecret"));
                    wechatConfigInfo.setTemplateId(jsonObject.getStr("templateId"));
                }
            }
            //客户在开启微信报警时，公众号信息必填
            if (wechatConfigInfo != null) {
                // 发送给所有关注用户
                wechatSendMessage.sendTemplateMessage(wechatConfigInfo.getAppID(), wechatConfigInfo.getAppsecret(),
                        customerId, wechatConfigInfo.getTemplateId(), wechatAlarmData);
            }
        }
    }

    /**
     * 记录推送日志
     */
    public void logSendStatus(String target, String method, String status, String content, Long customerId) {
        AlarmSendLog alarmSendLog = new AlarmSendLog();
        alarmSendLog.setSendContent(content);
        alarmSendLog.setSendMethod(method);
        alarmSendLog.setSendTarget(target);
        alarmSendLog.setSendStatus(status);
        alarmSendLog.setCustomerId(customerId);
        alarmSendLog.setCreateBy(SecurityUtils.getUsername());
        alarmSendLogService.insertAlarmSendLog(alarmSendLog);
    }

    /**
     * 保存推送设置
     *
     * @param jsonArray 推送设置数据
     * @return 结果
     */
    @Override
    public HashMap<String, String> saveAlarmSend(JSONArray jsonArray) {
        AlarmSend alarmSend = null;
        HashMap<String, String> idmap = new HashMap<>();
        ObjectMapper objectMapper = new ObjectMapper();
        int row = 0;
        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject jsonObject = jsonArray.getJSONObject(i);

            // Convert the 'alarmCategories' field to a string
            List<String> alarmCategories = (List<String>) jsonObject.get("alarmCategories");
            String alarmCategoriesStr = String.join(",", alarmCategories);
            jsonObject.put("alarmCategories", alarmCategoriesStr);

            // Convert the 'alarmTargets' field to a string
            List<String> alarmTargets = (List<String>) jsonObject.get("alarmTargets");
            String alarmTargetsStr = String.join(",", alarmTargets);
            jsonObject.put("alarmTargets", alarmTargetsStr);

            List<Map<String, List<String>>> arrayData = (List<Map<String, List<String>>>) jsonObject.get("arrayData");
            List<List<String>> processedData = new ArrayList<>();
            for (Map<String, List<String>> data : arrayData) {
                processedData.add(data.get("time"));
            }

            // 使用 ObjectMapper 将列表转换为 JSON 字符串
            String alarmTimePeriodsStr = null;
            try {
                alarmTimePeriodsStr = objectMapper.writeValueAsString(processedData);
            } catch (JsonProcessingException e) {
                log.error(e.getMessage());
                e.printStackTrace();
            }
            jsonObject.put("alarmTimePeriods", alarmTimePeriodsStr);

            alarmSend = objectMapper.convertValue(jsonObject, AlarmSend.class);
            // 如果告警发送ID为空，则插入新的告警发送记录
            if (alarmSend.getAlarmSendId() == null) {
                alarmSend.setCreateBy(SecurityUtils.getUsername());
                row += insertAlarmSend(alarmSend);
                if (alarmSend.getAlarmMethod().equals(SendCategoryEnums.SEND_BY_EMAIL.getKey())) {
                    idmap.put("email", String.valueOf(alarmSend.getAlarmSendId()));
                } else if (alarmSend.getAlarmMethod().equals(SendCategoryEnums.SEND_BY_SMS.getKey())) {
                    idmap.put("sms", String.valueOf(alarmSend.getAlarmSendId()));
                } else if (alarmSend.getAlarmMethod().equals(SendCategoryEnums.SEND_BY_SOUND.getKey())) {
                    idmap.put("sound", String.valueOf(alarmSend.getAlarmSendId()));
                } else if (alarmSend.getAlarmMethod().equals(SendCategoryEnums.SEND_BY_WECHAT.getKey())) {
                    idmap.put("wechat", String.valueOf(alarmSend.getAlarmSendId()));
                }
            } else {
                alarmSend.setUpdateBy(SecurityUtils.getUsername());
                row += updateAlarmSend(alarmSend);
            }
        }
        return idmap;
    }
}
