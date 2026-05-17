package com.hpis.alarm.service.support;

import com.hpis.alarm.dto.AlarmInsertCommand;
import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.domain.DeviceKeyInfoDTO;
import com.hpis.common.core.enums.IrTypeEnums;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.redis.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 报警新增链路的设备缓存解析器。
 *
 * <p>这里集中维护报警入库前的设备 Redis 查询规则，避免 {@code insertAlarm} 中继续堆积行业分支。
 * 第一阶段只搬迁旧规则，不改变外部入口、push 消息体和持久化顺序。</p>
 *
 * <p>当前 key 边界：</p>
 * <ul>
 *     <li>局放：{@code Constants.DEVICE_SN_KEY + deviceSn + pdData.sensorId}</li>
 *     <li>温度传感器：{@code Constants.DEVICE_SN_KEY + deviceSn}；维也里来源取逗号前第一个 sn</li>
 *     <li>红外/可见光：先查 {@code Constants.DEVICE_SN_KEY + deviceSn + cameraType}，再回退查 {@code Constants.DEVICE_SN_KEY + deviceSn}</li>
 *     <li>tenantId 补全：{@code Constants.DEVICE_ID_KEY + deviceId}</li>
 * </ul>
 *
 * <p>禁止在这里创建测试设备或写死测试 tenantId。设备缓存缺失会抛出专用异常，
 * 由 MQ 消费链路记录后 ack 丢弃。</p>
 */
@Slf4j
@Component
public class AlarmDeviceResolver {

    @Autowired
    private RedisService redisService;

    /**
     * 解析报警所属设备。
     *
     * <p>返回的设备对象用于补充 targetName、tenantId、deviceSn 等入库字段。
     * 如果 Redis 中没有对应设备缓存，抛出 {@link AlarmDeviceCacheMissingException}，
     * 调用方会记录并丢弃该 MQ 消息，避免无效消息无限重试。</p>
     */
    public DeviceKeyInfoDTO resolve(AlarmInsertCommand command) {
        DeviceKeyInfoDTO device = resolveByScene(command);
        if (device == null) {
            log.warn("alarm insert device cache miss, alarmCid={}, deviceSn={}, sceneType={}, cameraType={}",
                    command.getAlarmCid(), command.getDeviceSn(), command.getSceneType(), command.getCameraType());
            throw new AlarmDeviceCacheMissingException(
                    command.getAlarmCid(), command.getDeviceSn(), command.getSceneType(), command.getCameraType());
        }
        fillTenantIdIfPossible(device, command);
        return device;
    }

    /**
     * 按报警行业选择设备缓存查询规则。
     */
    private DeviceKeyInfoDTO resolveByScene(AlarmInsertCommand command) {
        if (command.isPartialDischarge()) {
            return resolvePartialDischargeDevice(command);
        }
        if (isTemperatureSensor(command.getCameraType())) {
            return resolveTemperatureSensorDevice(command);
        }
        return resolveIrDevice(command);
    }

    /**
     * 局放报警通过 deviceSn + sensorId 定位设备。
     */
    private DeviceKeyInfoDTO resolvePartialDischargeDevice(AlarmInsertCommand command) {
        String sensorId = command.getPdData() == null ? null : command.getPdData().getString("sensorId");
        return redisService.getCacheObject(Constants.DEVICE_SN_KEY + command.getDeviceSn() + sensorId);
    }

    /**
     * 温度传感器按设备 sn 查询；维也里上报可能带多个 sn，只使用第一个 sn 兼容旧逻辑。
     */
    private DeviceKeyInfoDTO resolveTemperatureSensorDevice(AlarmInsertCommand command) {
        String deviceSn = command.getDeviceSn();
        if (command.isVieriSource() && StringUtils.isNotBlank(deviceSn)) {
            deviceSn = deviceSn.split(",")[0];
        }
        return redisService.getCacheObject(Constants.DEVICE_SN_KEY + deviceSn);
    }

    /**
     * 红外/可见光按 deviceSn + cameraType 查询，查不到时回退到 deviceSn。
     *
     * <p>如果上游没有传 cameraType，沿用旧逻辑默认补为红外 {@code ITEMS_0}，
     * 调用方会把默认值同步回原始 {@code JSONObject}，保证后续 breakLine 和 push 逻辑仍能读取。</p>
     */
    private DeviceKeyInfoDTO resolveIrDevice(AlarmInsertCommand command) {
        if (StringUtils.isBlank(command.getCameraType())) {
            command.setCameraType(IrTypeEnums.ITEMS_0.getKey());
        }

        DeviceKeyInfoDTO device = redisService.getCacheObject(
                Constants.DEVICE_SN_KEY + command.getDeviceSn() + command.getCameraType());
        if (device == null) {
            device = redisService.getCacheObject(Constants.DEVICE_SN_KEY + command.getDeviceSn());
        }
        return device;
    }

    /**
     * 尽量补齐 tenantId。
     *
     * <p>这里只从设备 id 缓存回查，不再写死 {@code 123L} 或构造测试设备。
     * 如果仍然缺失，只记录日志，让后续持久化链路暴露真实数据问题。</p>
     */
    private void fillTenantIdIfPossible(DeviceKeyInfoDTO device, AlarmInsertCommand command) {
        if (device.getTenantId() != null) {
            return;
        }
        DeviceKeyInfoDTO deviceById = redisService.getCacheObject(Constants.DEVICE_ID_KEY + device.getDeviceId());
        if (deviceById != null) {
            device.setTenantId(deviceById.getTenantId());
        }
        if (device.getTenantId() == null) {
            log.error("alarm insert device tenant missing, alarmCid={}, deviceId={}, deviceSn={}",
                    command.getAlarmCid(), device.getDeviceId(), command.getDeviceSn());
        }
    }

    /**
     * 温度传感器 cameraType 集合。
     */
    private boolean isTemperatureSensor(String cameraType) {
        return IrTypeEnums.ITEMS_10.getKey().equals(cameraType)
                || IrTypeEnums.ITEMS_500.getKey().equals(cameraType);
    }
}
