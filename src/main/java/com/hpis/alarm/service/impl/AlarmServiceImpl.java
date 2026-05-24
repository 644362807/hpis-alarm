package com.hpis.alarm.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Snowflake;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import javax.annotation.Nullable;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.config.AlarmInternalTestProperties;
import com.hpis.alarm.config.sharding.AlarmCidIndexService;
import com.hpis.alarm.config.sharding.AlarmMonthlySliceTableManager;
import com.hpis.alarm.config.sharding.AlarmShardContext;
import com.hpis.alarm.domain.*;
import com.hpis.alarm.dto.AlarmInsertCommand;
import com.hpis.alarm.dto.AlarmPartialDischargeDto;
import com.hpis.alarm.dto.AlarmQueryParameter;
import com.hpis.alarm.dto.RepeatAlarmDto;
import com.hpis.alarm.enums.*;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.service.*;
import com.hpis.alarm.service.support.AlarmDeviceCacheMissingException;
import com.hpis.alarm.service.support.AlarmElectrolyticCellInvalidException;
import com.hpis.alarm.service.support.AlarmDeviceResolver;
import com.hpis.alarm.service.support.DisconnectAlarmDeduplicator;
import com.hpis.alarm.transfer.RabbitMQAlarmPushProducer;

import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.constant.OperCodeConstants;
import com.hpis.common.core.constant.WebsocketStatus;
import com.hpis.common.core.domain.DUnitInfoDto;
import com.hpis.common.core.domain.DeviceAreaKeyInfoDTO;
import com.hpis.common.core.domain.DeviceKeyInfoDTO;
import com.hpis.common.core.domain.R;
import com.hpis.common.core.enums.*;
import com.hpis.common.core.exception.BaseException;
import com.hpis.common.core.exception.CustomException;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.ElecCellPackMerNameUtil;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.core.utils.file.FileConvertBase64Util;
import com.hpis.common.core.utils.file.FileUtils;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;

import com.hpis.common.websocket.WebSocketKeepAliveClient;
import com.hpis.common.websocket.model.TransferCommandObject;
import com.hpis.common.websocket.util.CommonTranferUtil;
import com.hpis.device.api.RemoteDUnitInfoService;
import com.hpis.device.api.RemoteDeviceAreaService;
import com.hpis.device.api.RemoteIrChannelService;
import com.hpis.device.api.RemoteTmService;
import com.hpis.system.api.RemoteFileService;
import com.hpis.system.api.domain.SysFile;
import com.hpis.tmAnalysis.api.RemoteTmActionService;
import com.hpis.tmAnalysis.api.domian.TmActionDto;
import com.mysql.cj.jdbc.exceptions.MySQLQueryInterruptedException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


/**
 * 【请填写功能名称】Service业务层处理
 *
 * @author ruoyi
 * @date 2023-03-21
 */
@Slf4j
@Service
@RefreshScope
public class AlarmServiceImpl extends ServiceImpl<AlarmMapper, Alarm> implements IAlarmService
{

	@Autowired
	private AlarmMapper alarmMapper;

	/**
	 * 临时存储在本地的根路径
	 */
	@Value("${file.path}")
	private String localFilePath;

	@Value("${push.open:false}")
	private boolean pushOpen;

	@Autowired
	private RemoteFileService remoteFileService ;

	@Autowired
	@Nullable
	private WebSocketKeepAliveClient webSocketClient;

	@Autowired
	private IAlarmElectrolyticCellService iAlarmElectrolyticCellService;

	@Autowired
	private IAlarmPartialDischargeService iAlarmPartialDischargeService;

	@Autowired
	private RemoteIrChannelService remoteIrChannelService;

	@Autowired
	private RemoteTmService remoteTmService;

	@Autowired
	private RemoteTmActionService remoteTmActionService;
//
//	@Autowired
//	private RemoteElectrolyticSequenceService remoteElectrolyticSequenceService;

	@Autowired
	private RemoteDUnitInfoService remoteDUnitInfoService;
	@Autowired
	private AlarmHandleMapper alarmHandleMapper;

	@Autowired
	private NacosDiscoveryProperties nacosDiscoveryProperties;

	@Autowired
	private RemoteDeviceAreaService remoteDeviceAreaService;

	@Autowired
	private IAlarmSendService alarmSendService;

	@Autowired
	private RabbitMQAlarmPushProducer rabbitMQAlarmPushProducer;


	@Autowired
	private ThreadPoolTaskExecutor threadPoolExecutor;

	@Autowired
	private RedisService redisService;
	@Autowired
	private IAlarmConfigureService iAlarmConfigureService;

	@Autowired(required = false)
	private AlarmCidIndexService alarmCidIndexService;

	@Autowired(required = false)
	private AlarmStopEventService alarmStopEventService;

	@Autowired
	private AlarmDeviceResolver alarmDeviceResolver;

	@Autowired
	private DisconnectAlarmDeduplicator disconnectAlarmDeduplicator;

	@Autowired
	private AlarmInternalTestProperties internalTestProperties;

	@Autowired
	private AlarmBatchProperties batchProperties;

	@Autowired
	private PlatformTransactionManager transactionManager;

//	private static HashMap<String, Integer> handleSceneMap = new HashMap<>();
//	private static HashMap<String, Integer> ecSceneMap = new HashMap<>();
//	private static HashMap<String, Integer> pdSceneMap = new HashMap<>();
//
//	//有关行业 再主表 处理表的记录 key：1.一般行业;2.电解槽行业;3.集热器行业;4.回转窑行业;5.电力行业;6.局放行业;11.维耶里行业  1：ture 0:flase
//	static	{
//		//报警处理表
//		handleSceneMap.put("1", 1);
//		handleSceneMap.put("2", 1);
//		handleSceneMap.put("6", 0);
//		handleSceneMap.put("11",0);
//
//		ecSceneMap.put("1", 1);
//		ecSceneMap.put("2", 0);
//		ecSceneMap.put("6", 0);
//		ecSceneMap.put("11", 0);
//
//		pdSceneMap.put("1", 1);
//		pdSceneMap.put("2",0);
//		pdSceneMap.put("6", 0);
//		pdSceneMap.put("11", 0);
//	}

	private static final Snowflake snowflake = new Snowflake(5, 5);


	/**
	 * 查询【请填写功能名称】
	 *
	 * @param alarmId 【请填写功能名称】ID
	 * @return 【请填写功能名称】
	 */
	@Override
	public Alarm selectAlarmById(Long alarmId)
	{
		return alarmMapper.selectAlarmById(alarmId);
	}

	@Override
	public Page<Alarm> selectAlarmPage(Alarm alarm) {
		Long currentTenantId = SecurityUtils.getCurrentTenantId();
		alarm.setTenantId(currentTenantId);
		QueryWrapper<Alarm> queryWrapper = new QueryWrapper<>();
		queryWrapper.eq(StringUtils.isNotBlank(alarm.getSceneType()), "a.scene_type", alarm.getSceneType())
				.eq(StringUtils.isNotBlank(alarm.getAlarmType()), "a.alarm_type", alarm.getAlarmType())
				.eq(StringUtils.isNotBlank(alarm.getAlarmRank()), "a.alarm_rank", alarm.getAlarmRank())
				.eq(StringUtils.isNotBlank(alarm.getAlarmStatus()), "a.alarm_status", alarm.getAlarmStatus())
				.eq(alarm.getDeviceSn() != null, "a.device_sn", alarm.getDeviceSn())
				.eq(alarm.getTenantId() != null, "a.tenant_id", alarm.getTenantId())
				.like(StringUtils.isNotBlank(alarm.getTargetName()), "a.target_name", alarm.getTargetName());
		// 添加开始时间条件
		if (alarm.getStartTime() != null) {
			queryWrapper.gt("a.alarm_beginTime", alarm.getStartTime());
		}

		// 添加结束时间条件
		if (alarm.getEndTime() != null) {
			queryWrapper.lt("a.alarm_beginTime", alarm.getEndTime());
		}

		Page<Alarm> alarmPage = this.baseMapper.selectAlarmListPage(new Page<>(alarm.getPageNum(), alarm.getPageSize()), queryWrapper);
		List<Alarm> records = alarmPage.getRecords();
		List<String> deviceIdList = new ArrayList<>();
		for (Alarm pd : records) {
			if (StringUtils.isNotBlank(pd.getDeviceSn())) {
				String[] split = pd.getDeviceSn().split(",");

				deviceIdList.addAll(Arrays.asList(split));
			}

		}
		List<String> uniqueDeviceIdList = deviceIdList.stream()
				.distinct()
				.collect(Collectors.toList());
		Map<String, String> deviceMap = new HashMap<>();

		for (String sn : uniqueDeviceIdList) {
			DeviceKeyInfoDTO device = redisService.getCacheObject(Constants.DEVICE_SN_KEY + sn);
			deviceMap.put(sn, device != null ? device.getDeviceName() : "");
		}

		for (Alarm pd : records) {
			if (StringUtils.isNotBlank(pd.getDeviceSn())) {
				StringBuilder sb = new StringBuilder();
				String[] split = pd.getDeviceSn().split(",");
				if (split.length > 1) {
					for (String s : split) {
						sb.append(deviceMap.get(s) + ",");
					}
					pd.setDeviceName(sb.toString());
				} else {
					pd.setDeviceName(deviceMap.get(split[0]));
				}


			}
		}

		return alarmPage;
	}

//	void sceneTypeShowHandle(Alarm alarm)

	@Override
	public Long countAlarm(Alarm alarm) {
		Long currentTenantId = SecurityUtils.getCurrentTenantId();
		alarm.setTenantId(currentTenantId);
		QueryWrapper<Alarm> queryWrapper = new QueryWrapper<>();
		queryWrapper.eq(StringUtils.isNotBlank(alarm.getSceneType()),"scene_type",alarm.getSceneType());
		queryWrapper.eq(StringUtils.isNotBlank(alarm.getAlarmType()),"alarm_type",alarm.getAlarmType());
		queryWrapper.eq(StringUtils.isNotBlank(alarm.getAlarmRank()),"alarm_rank",alarm.getAlarmRank());
		queryWrapper.eq(StringUtils.isNotBlank(alarm.getAlarmStatus()),"alarm_status",alarm.getAlarmStatus());
		queryWrapper.eq(alarm.getDeviceSn()!=null,"device_sn",alarm.getDeviceSn());
		queryWrapper.eq(alarm.getTenantId()!=null,"tenant_id",alarm.getTenantId());
		// 添加开始时间条件
		if (alarm.getStartTime() != null) {
			queryWrapper.gt("alarm_beginTime", alarm.getStartTime());
		}

		// 添加结束时间条件
		if (alarm.getEndTime() != null) {
			queryWrapper.lt( "alarm_beginTime",alarm.getEndTime());
		}
		Long aLong = alarmMapper.selectCount(queryWrapper);
		return aLong;
	}



	/**
	 * 查询【请填写功能名称】列表
	 *
	 * @param alarm 【请填写功能名称】
	 * @return 【请填写功能名称】
	 */
	@Override
	public List<Alarm> selectAlarmList(Alarm alarm,Long customerId)
	{

		return alarmMapper.selectAlarmList(alarm);
	}

	void sceneTypeDataHandle(JSONObject jsonObject,Alarm alarm){

		if (SceneTypeEnums.SCENE_TYPE_11.getKey() == jsonObject.getIntValue("sceneType")){
			jsonObject.put("sceneType",SceneTypeEnums.SCENE_TYPE_1.getKey());
			jsonObject.put("srcSceneType",SceneTypeEnums.SCENE_TYPE_11.getKey());
			alarm.setSceneType(jsonObject.getString("srcSceneType"));

			JSONObject object = new JSONObject();

			if (jsonObject.containsKey("tubeColor") && StringUtils.isNotBlank(jsonObject.getString("tubeColor"))) {
				object.put("color",jsonObject.getString("color"));
			}
			if (jsonObject.containsKey("colorTubeName") && StringUtils.isNotBlank(jsonObject.getString("colorTubeName"))) {
				object.put("colorTubeName",jsonObject.getString("colorTubeName"));
			}

			String JSONString = object.toJSONString();
			if (StringUtils.isNotBlank(JSONString)){
				alarm.setRemarkData(JSONString);
			}

		}

	}

	/**
	 * 新增报警主流程。
	 *
	 * <p>外部入口继续保持 {@code JSONObject} 不变，避免影响 Controller、MQ listener 和旧调用方。
	 * 第一阶段只把关键步骤显式化：生成 traceId、解析设备缓存、断线报警 Redis 去重、组装 Alarm、调用旧持久化链路。</p>
	 *
	 * <p>异常边界：</p>
	 * <ul>
	 *     <li>设备缓存缺失：抛出专用异常，由 MQ listener 记录后 ack 丢弃。</li>
	 *     <li>断线 Redis 去重异常：继续入库，不能因为 Redis 故障丢失报警。</li>
	 *     <li>DB/组装异常：继续抛出，让 MQ listener requeue 或上层事务回滚。</li>
	 * </ul>
	 *
	 * @param jsonObject MQ/接口传入的原始报警 JSON，字段结构和 push 兼容性保持不变
	 */
	@Override
	@Transactional(rollbackFor = {Exception.class})
	public void insertAlarm(JSONObject jsonObject) {
		insertAlarmSingle(jsonObject);
	}

	@Override
	public void insertAlarms(List<JSONObject> jsonObjects) {
		/*
		 * 内部批量入口，不改变 Controller/MQ 原有单条入口。
		 * 批量开关关闭或只有一条数据时，仍走单条事务，作为生产快速回滚路径。
		 */
		if (jsonObjects == null || jsonObjects.isEmpty()) {
			return;
		}
		if (!batchProperties.isInsertEnabled() || jsonObjects.size() == 1) {
			for (JSONObject jsonObject : jsonObjects) {
				executeAlarmBatchTransaction(() -> insertAlarmSingle(jsonObject));
			}
			return;
		}
		insertAlarmsBatch(jsonObjects);
	}

	private void executeAlarmBatchTransaction(Runnable action) {
		/*
		 * 批量/兜底持久化都使用 REQUIRES_NEW。
		 * 这样 MQ listener 或上层调用方不需要感知内部批量事务边界；批量失败后拆单也能逐条独立提交。
		 */
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transactionTemplate.executeWithoutResult(status -> action.run());
	}

	private void insertAlarmSingle(JSONObject jsonObject) {
		String traceId = buildAlarmInsertTraceId(jsonObject);
		long insertStartMs = System.currentTimeMillis();
		DisconnectAlarmDeduplicator.DedupResult dedupResult = null;
		boolean insertCompleted = false;
		try {
			Alarm alarm = new Alarm();
			//雪花主键
//			long alarmId = snowflake.nextId();
//
//			alarm.setAlarmId(alarmId);
			alarm.setSceneType(jsonObject.getString("sceneType"));

			//出现真实行业和 逻辑行业（目前仅仅维也里有）
			sceneTypeDataHandle(jsonObject,alarm);

			String deviceSn = jsonObject.getString("deviceSn");
			alarm.setAlarmCid(jsonObject.getString("alarmId"));
			AlarmInsertCommand command = AlarmInsertCommand.from(jsonObject);
			logAlarmInsertStage(traceId, "START", "BEGIN", insertStartMs, jsonObject, alarm, null);

			//获取设备信息
			DeviceKeyInfoDTO device = alarmDeviceResolver.resolve(command);
			if (StringUtils.isNotBlank(command.getCameraType())) {
				jsonObject.put("cameraType", command.getCameraType());
			}
			logAlarmInsertStage(traceId, "RESOLVE_DEVICE", "SUCCESS", insertStartMs, jsonObject, alarm, null);

			alarm.setTargetName(device.getDeviceName());
			//deviceSn 维耶里温度报警的时候 使用原始sn
			if (jsonObject.containsKey("srcSceneType") && SceneTypeEnums.SCENE_TYPE_11.getKey() == jsonObject.getIntValue("srcSceneType")&& jsonObject.getString("alarmType").equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getKey())) {
				alarm.setDeviceSn(deviceSn);
			}else {
				alarm.setDeviceSn(deviceSn);
			}


			alarm.setTenantId(device.getTenantId());
			//参数 --字典匹配
			String alarmType = jsonObject.getString("alarmType");
			if (StringUtils.isNotBlank(alarmType)) {
				//高温报警
				if (alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getKey())) {
					alarm.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getDescription());

					//断线报警
				} else if (alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())) {

					dedupResult = disconnectAlarmDeduplicator.tryAcquire(command, traceId);
					if (dedupResult.isDuplicate()){
						logAlarmInsertStage(traceId, "DISCONNECT_DEDUP", "SKIP_DUPLICATE", insertStartMs, jsonObject, alarm, null);
						log.warn("断线报警正在处理或重复上报，本次跳过，alarmCid={}", alarm.getAlarmCid());
						return;
					}
					//由于网关掉线会全部停止 然后全部推送可能有cid重复 因此取消该查询
//					Alarm alarmId1 = alarmMapper.selectAlarmByCid(jsonObject.getString("alarmId"));
//					if (alarmId1 != null) {
//						return;
//					}
					alarm.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription());
					//断线报警更新设备通讯状态

					/**由于硝化棉 之后设备掉线的状态同步 直接发送device 暂不需要由报警同步       */
					breakLine(jsonObject.getString("cameraType"), device, alarm);

					/***********——————————————————————————————————————————————————*************/
					//设置报警目标名称为枚举，用于报警停止区分红外可见
					alarm.setTargetName(IrTypeEnums.getValue(jsonObject.getString("cameraType")));
					if (jsonObject.containsKey("srcSceneType") && SceneTypeEnums.SCENE_TYPE_11.getKey() == jsonObject.getIntValue("srcSceneType")&&!IrTypeEnums.ITEMS_10.getKey().equals(jsonObject.getString("cameraType"))) {
						breakLineUnitAction(jsonObject.getString("deviceSn"));
					}
					//断线报警停止其sn下全部报警
//					alarmStopByDeviceSn(jsonObject);

				}else {
					alarm.setAlarmType(alarmType);
				}

			}
			jsonTransformJava(jsonObject,alarm);
			/*
			 * alarm_id 必须在处理表、电解槽扩展表、cid route 之前生成。
			 * 这里仍然沿用现有分片分配器，不改变外部 alarmId/alarmCid 语义；只是把内部主键提前到子表组装之前，
			 * 保证 alarm、alarm_handle、alarm_electrolytic_cell、alarm_cid_index 使用同一个主表 ID。
			 */
			prepareAlarmShard(alarm);
			logAlarmInsertStage(traceId, "BUILD_ALARM", "SUCCESS", insertStartMs, jsonObject, alarm, null);
			//添加报警处理列表
			try {
			if (jsonObject.getIntValue("sceneType") != SceneTypeEnums.SCENE_TYPE_6.getKey()) {

				alarm =	insetAlarmHandle(alarm,jsonObject,device);
			}
			} catch (Exception e) {
				log.error("处理表插入报警报错:{}", e.getMessage());
				throw new CustomException(e.getMessage());
			}
			//电解槽关联报警
			AlarmElectrolyticCell alarmElectrolyticCell = new AlarmElectrolyticCell();
			try {
				if (!alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey()) &&
					jsonObject.getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
					//用于电解槽信息组装
					alarmElectrolyticCell = insetElectrolyticCell(jsonObject, alarm);
//					jsonObject.put("alarmElectrolyticCell",alarmElectrolyticCell);
				}
			} catch (AlarmElectrolyticCellInvalidException e) {
				throw e;
			} catch (Exception e) {
				log.error("电解槽信息组装报警报错:{}", e.getMessage());
				throw new CustomException(e.getMessage());
			}

			insertSql(alarmType,jsonObject ,alarm,alarmElectrolyticCell);
			insertCompleted = true;
			logAlarmInsertStage(traceId, "PERSIST", "SUCCESS", insertStartMs, jsonObject, alarm, null);
		} catch (AlarmDeviceCacheMissingException e) {
			logAlarmInsertStage(traceId, "RESOLVE_DEVICE", "DROP_DEVICE_CACHE_MISS", insertStartMs, jsonObject, null, e.getMessage());
			throw e;
		} catch (AlarmElectrolyticCellInvalidException e) {
			logAlarmInsertStage(traceId, "BUILD_ELECTROLYTIC_CELL", "DROP_INVALID_ELECTROLYTIC_CELL", insertStartMs, jsonObject, null, e.getMessage());
			throw e;
		} catch (Exception e) {
			logAlarmInsertStage(traceId, "INSERT_ALARM", "FAIL", insertStartMs, jsonObject, null, e.getMessage());
			log.error("报警插入异常:{}", e.getMessage());
			if (e instanceof MySQLQueryInterruptedException) {
				throw new CustomException("Database operation interrupted");
			} else {
				throw new RuntimeException(e);
			}
		} finally {
			if (!insertCompleted) {
				AlarmShardContext.clear();
				disconnectAlarmDeduplicator.releaseOnFailure(dedupResult, traceId, jsonObject.getString("alarmId"));
			}
		}
	}

	/**
	 * 内部批量新增报警主流程。
	 *
	 * <p>本方法先逐条构建 {@link AlarmInsertContext}，再按分片 suffix 批量入库。
	 * 逐条 prepare 是为了保持设备缓存解析、断线 Redis 去重、远程状态同步的旧语义；
	 * 批量收益主要来自主表、处理表、扩展表和 cid route 的批量持久化。</p>
	 *
	 * <p>失败边界：批量事务任一环节失败会整体回滚；如果开启
	 * {@code fallbackSingleOnBatchError}，后续逐条复用已构建 context 单条入库，不能重新 prepare，
	 * 避免 Redis 去重和远程副作用重复执行。</p>
	 */
	private void insertAlarmsBatch(List<JSONObject> jsonObjects) {
		String batchId = UUID.randomUUID().toString().replace("-", "");
		long startMs = System.currentTimeMillis();
		List<AlarmInsertContext> contexts = new ArrayList<>();
		boolean persistAttempted = false;
		try {
			for (JSONObject jsonObject : jsonObjects) {
				AlarmInsertContext context = prepareAlarmInsertContext(jsonObject);
				if (context != null && !context.isSkipped()) {
					contexts.add(context);
				}
			}
			persistAttempted = true;
			persistPreparedAlarmBatch(batchId, contexts);
			log.info("alarm insert batch stage=INSERT_BATCH_PERSIST batchId={}, batchSize={}, persistedCount={}, sampleAlarmCids={}, costMs={}",
					batchId, jsonObjects.size(), contexts.size(), sampleAlarmInsertCids(jsonObjects),
					System.currentTimeMillis() - startMs);
		} catch (Exception ex) {
			if (!batchProperties.isFallbackSingleOnBatchError()) {
				throw new RuntimeException(ex);
			}
			log.warn("alarm insert batch stage=FALLBACK_SINGLE batchId={}, batchSize={}, preparedCount={}, sampleAlarmCids={}, error={}",
					batchId, jsonObjects.size(), contexts.size(), sampleAlarmInsertCids(jsonObjects), ex.getMessage(), ex);
			RuntimeException firstFailure = null;
			for (AlarmInsertContext context : contexts) {
				try {
					// 批量回滚后的单条兜底必须复用 prepared context，不能再次执行 Redis 去重和远程组装副作用。
					persistPreparedAlarmSingle(batchId, context);
				} catch (Exception singleEx) {
					releasePreparedAlarmOnFailure(context);
					if (firstFailure == null) {
						firstFailure = singleEx instanceof RuntimeException
								? (RuntimeException) singleEx : new RuntimeException(singleEx);
					}
				}
			}
			if (firstFailure != null) {
				throw firstFailure;
			}
			if (!persistAttempted) {
				throw ex instanceof RuntimeException ? (RuntimeException) ex : new RuntimeException(ex);
			}
		} finally {
			for (AlarmInsertContext context : contexts) {
				releasePreparedAlarmOnFailure(context);
			}
		}
	}

	/**
	 * 构建可复用的报警插入上下文。
	 *
	 * <p>该方法会执行设备解析、断线 Redis 去重、报警对象组装和部分远程状态同步。
	 * 对同一条 MQ 消息只能调用一次；批量回滚后的单条兜底必须复用返回的 context，
	 * 否则会重复抢占 Redis 去重 key 或重复触发远程状态同步。</p>
	 */
	public AlarmInsertContext prepareAlarmInsertContext(JSONObject jsonObject) {
		return buildAlarmInsertContext(jsonObject);
	}

	/**
	 * 在一个事务中持久化一批 prepared context。
	 *
	 * <p>completion 标记只有在 {@link TransactionTemplate} 成功返回后才设置。
	 * 如果事务提交失败，调用方仍可以释放已抢占的断线 Redis 去重 key，或者继续拆单兜底。</p>
	 */
	public void persistPreparedAlarmBatch(String batchId, List<AlarmInsertContext> contexts) {
		if (contexts == null || contexts.isEmpty()) {
			return;
		}
		executeAlarmBatchTransaction(() -> persistAlarmInsertContexts(batchId, contexts));
		for (AlarmInsertContext context : contexts) {
			context.setInsertCompleted(true);
			logAlarmInsertStage(context.getTraceId(), "PERSIST", "SUCCESS", context.getInsertStartMs(),
					context.getJsonObject(), context.getAlarm(), null);
		}
	}

	/**
	 * 持久化单个 prepared context，且不重新构建报警对象。
	 *
	 * <p>这是 consumer batch/内部批量 insert 的回滚兜底入口。它保持 push payload 和 afterCommit 时机与旧单条插入一致，
	 * 同时避免第二次执行 Redis tryAcquire。</p>
	 */
	public void persistPreparedAlarmSingle(String batchId, AlarmInsertContext context) {
		if (context == null || context.isSkipped()) {
			return;
		}
		executeAlarmBatchTransaction(() -> persistPreparedAlarmSingleInTransaction(batchId, context));
		context.setInsertCompleted(true);
		logAlarmInsertStage(context.getTraceId(), "PERSIST", "SUCCESS", context.getInsertStartMs(),
				context.getJsonObject(), context.getAlarm(), null);
	}

	/** 只有 prepared context 最终没有成功提交时才释放断线去重 key，避免成功入库后被重复报警再次抢占。 */
	public void releasePreparedAlarmOnFailure(AlarmInsertContext context) {
		if (context == null || context.isInsertCompleted()) {
			return;
		}
		disconnectAlarmDeduplicator.releaseOnFailure(context.getDedupResult(), context.getTraceId(),
				context.getJsonObject() == null ? null : context.getJsonObject().getString("alarmId"));
	}

	private AlarmInsertContext buildAlarmInsertContext(JSONObject jsonObject) {
		/*
		 * 该方法是内部批量链路和 Spring AMQP consumer batch 的 prepare 阶段。
		 * 任何会产生副作用的动作都集中在这里执行一次，后续只允许持久化 context。
		 */
		String traceId = buildAlarmInsertTraceId(jsonObject);
		long insertStartMs = System.currentTimeMillis();
		DisconnectAlarmDeduplicator.DedupResult dedupResult = null;
		try {
			Alarm alarm = new Alarm();
			alarm.setSceneType(jsonObject.getString("sceneType"));
			sceneTypeDataHandle(jsonObject, alarm);
			String deviceSn = jsonObject.getString("deviceSn");
			alarm.setAlarmCid(jsonObject.getString("alarmId"));
			AlarmInsertCommand command = AlarmInsertCommand.from(jsonObject);
			logAlarmInsertStage(traceId, "START", "BEGIN", insertStartMs, jsonObject, alarm, null);

			DeviceKeyInfoDTO device = alarmDeviceResolver.resolve(command);
			if (StringUtils.isNotBlank(command.getCameraType())) {
				jsonObject.put("cameraType", command.getCameraType());
			}
			logAlarmInsertStage(traceId, "RESOLVE_DEVICE", "SUCCESS", insertStartMs, jsonObject, alarm, null);

			alarm.setTargetName(device.getDeviceName());
			alarm.setDeviceSn(deviceSn);
			alarm.setTenantId(device.getTenantId());
			String alarmType = jsonObject.getString("alarmType");
			if (StringUtils.isNotBlank(alarmType)) {
				if (alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getKey())) {
					alarm.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getDescription());
				} else if (alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())) {
					dedupResult = disconnectAlarmDeduplicator.tryAcquire(command, traceId);
					if (dedupResult.isDuplicate()) {
						// 重复断线报警直接返回 skipped context，调用方应 ack/SKIP，不再进入持久化。
						logAlarmInsertStage(traceId, "DISCONNECT_DEDUP", "SKIP_DUPLICATE", insertStartMs, jsonObject, alarm, null);
						log.warn("断线报警正在处理或重复上报，本次跳过，alarmCid={}", alarm.getAlarmCid());
						AlarmInsertContext skipped = new AlarmInsertContext();
						skipped.setTraceId(traceId);
						skipped.setInsertStartMs(insertStartMs);
						skipped.setJsonObject(jsonObject);
						skipped.setAlarm(alarm);
						skipped.setAlarmType(alarmType);
						skipped.setDedupResult(dedupResult);
						skipped.setSkipped(true);
						return skipped;
					}
					alarm.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription());
					breakLine(jsonObject.getString("cameraType"), device, alarm);
					alarm.setTargetName(IrTypeEnums.getValue(jsonObject.getString("cameraType")));
					if (jsonObject.containsKey("srcSceneType") && SceneTypeEnums.SCENE_TYPE_11.getKey() == jsonObject.getIntValue("srcSceneType")
							&& !IrTypeEnums.ITEMS_10.getKey().equals(jsonObject.getString("cameraType"))) {
						breakLineUnitAction(jsonObject.getString("deviceSn"));
					}
				} else {
					alarm.setAlarmType(alarmType);
				}
			}
			jsonTransformJava(jsonObject, alarm);
			/*
			 * 批量链路在 prepare 阶段先生成内部 alarm_id，并把分片 suffix 保存到 context。
			 * 后续批量事务只复用这个 ID/suffix，不重新执行 Redis 去重和扩展信息组装，避免批量失败拆单时子表 alarm_id 漂移。
			 */
			String preparedShardSuffix = prepareAlarmShard(alarm);
			AlarmShardContext.clear();
			logAlarmInsertStage(traceId, "BUILD_ALARM", "SUCCESS", insertStartMs, jsonObject, alarm, null);
			if (jsonObject.getIntValue("sceneType") != SceneTypeEnums.SCENE_TYPE_6.getKey()) {
				alarm = insetAlarmHandle(alarm, jsonObject, device);
			}

			AlarmElectrolyticCell alarmElectrolyticCell = new AlarmElectrolyticCell();
			if (!alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())
					&& jsonObject.getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
				alarmElectrolyticCell = insetElectrolyticCell(jsonObject, alarm);
			}

			AlarmInsertContext context = new AlarmInsertContext();
			context.setTraceId(traceId);
			context.setInsertStartMs(insertStartMs);
			context.setDedupResult(dedupResult);
			context.setJsonObject(jsonObject);
			context.setAlarm(alarm);
			context.setAlarmType(alarmType);
			context.setAlarmElectrolyticCell(alarmElectrolyticCell);
			context.setShardSuffix(preparedShardSuffix);
			return context;
		} catch (AlarmDeviceCacheMissingException e) {
			logAlarmInsertStage(traceId, "RESOLVE_DEVICE", "DROP_DEVICE_CACHE_MISS", insertStartMs, jsonObject, null, e.getMessage());
			disconnectAlarmDeduplicator.releaseOnFailure(dedupResult, traceId, jsonObject.getString("alarmId"));
			throw e;
		} catch (AlarmElectrolyticCellInvalidException e) {
			logAlarmInsertStage(traceId, "BUILD_ELECTROLYTIC_CELL", "DROP_INVALID_ELECTROLYTIC_CELL", insertStartMs, jsonObject, null, e.getMessage());
			disconnectAlarmDeduplicator.releaseOnFailure(dedupResult, traceId, jsonObject.getString("alarmId"));
			throw e;
		} catch (Exception e) {
			logAlarmInsertStage(traceId, "INSERT_ALARM", "FAIL", insertStartMs, jsonObject, null, e.getMessage());
			disconnectAlarmDeduplicator.releaseOnFailure(dedupResult, traceId, jsonObject.getString("alarmId"));
			throw new RuntimeException(e);
		}
	}

	private void persistAlarmInsertContexts(String batchId, List<AlarmInsertContext> contexts) {
		if (contexts.isEmpty()) {
			return;
		}
		/*
		 * 先为每条报警分配分片，再按 suffix 聚合。
		 * AlarmShardContext 是线程本地上下文，分组前后必须 clear，避免同一线程处理下一批时串分片。
		 */
		Map<String, List<AlarmInsertContext>> grouped = new LinkedHashMap<>();
		for (AlarmInsertContext context : contexts) {
			String shardSuffix = context.getShardSuffix();
			if (StringUtils.isBlank(shardSuffix) || context.getAlarm().getAlarmId() == null) {
				shardSuffix = prepareAlarmShard(context.getAlarm());
				context.setShardSuffix(shardSuffix);
				AlarmShardContext.clear();
			}
			grouped.computeIfAbsent(shardSuffix, key -> new ArrayList<>()).add(context);
		}
		for (Map.Entry<String, List<AlarmInsertContext>> entry : grouped.entrySet()) {
			persistAlarmInsertGroup(batchId, entry.getKey(), entry.getValue());
		}
	}

	private void persistAlarmInsertGroup(String batchId, String shardSuffix, List<AlarmInsertContext> contexts) {
		try {
			/*
			 * 同一个 suffix 下可以安全批量写主表、处理表、扩展表和 cid route。
			 * 整个 group 处于同一事务内，任一表写失败会回滚，避免只写主表不写 route 或扩展表的半成品。
			 */
			if (shardSuffix != null) {
				AlarmShardContext.setTableSuffix(shardSuffix);
			}
			List<Alarm> alarms = new ArrayList<>();
			List<AlarmHandle> handles = new ArrayList<>();
			List<AlarmElectrolyticCell> ecItems = new ArrayList<>();
			List<AlarmElectrolyticCell> ecEctypeItems = new ArrayList<>();
			List<AlarmPartialDischarge> pdItems = new ArrayList<>();
			for (AlarmInsertContext context : contexts) {
				Alarm alarm = context.getAlarm();
				alarm.setCreateTime(DateUtils.getNowDate());
				alarms.add(alarm);
				if (context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_1.getKey()
						|| context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
					AlarmHandle alarmHandle = new AlarmHandle();
					alarmHandle.setAlarmId(alarm.getAlarmId());
					alarmHandle.setAlarmBegintime(alarm.getAlarmBegintime());
					alarmHandle.setCreateTime(DateUtils.getNowDate());
					handles.add(alarmHandle);
				}
				if (!context.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())
						&& context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
					AlarmElectrolyticCell ec = context.getAlarmElectrolyticCell();
					bindElectrolyticCellToAlarm(ec, alarm, context.getJsonObject());
					ecItems.add(ec);
					ecEctypeItems.add(ec);
					context.getJsonObject().put("alarmElectrolyticCell", ec);
				}
				if (!context.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())
						&& context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_6.getKey()) {
					AlarmPartialDischarge pd = context.getAlarmPartialDischarge();
					if (pd == null) {
						pd = buildPartialDischargeForInsert(context.getJsonObject(), alarm);
						context.setAlarmPartialDischarge(pd);
					}
					pdItems.add(pd);
				}
			}
			if (!handles.isEmpty()) {
				alarmHandleMapper.insertAlarmHandelList(handles);
			}
			if (!ecItems.isEmpty()) {
				iAlarmElectrolyticCellService.insertAlarmElectrolyticCellList(ecItems);
			}
			if (!ecEctypeItems.isEmpty()) {
				iAlarmElectrolyticCellService.insertAlarmElectrolyticCellEctypeList(ecEctypeItems);
			}
			if (!pdItems.isEmpty()) {
				iAlarmPartialDischargeService.insertAlarmPartialDischargeList(pdItems);
			}
			int inserted = alarmMapper.insertAlarmBatch(alarms);
			if (inserted <= 0) {
				throw new CustomException("报警主表批量插入返回 0");
			}
			Map<String, AlarmCidRoute> routeByCid = new LinkedHashMap<>();
			if (shardSuffix != null && alarmCidIndexService != null) {
				List<AlarmCidRoute> routes = alarmCidIndexService.saveActiveHotRoutes(alarms, shardSuffix, batchProperties.safeInLimit());
				for (AlarmCidRoute route : routes) {
					routeByCid.put(route.getAlarmCid(), route);
				}
				if (alarmStopEventService != null) {
					// start 批量入库后立即批量补偿 stop 早到消息，避免 stop 已落库但新报警没有 endTime。
					alarmStopEventService.applyPendingStopsForNewAlarms(alarms, routeByCid);
				}
			}
			for (AlarmInsertContext context : contexts) {
				// push 仍保持旧格式逐条 afterCommit 发布，不能为了批量入库改变旧消费者协议。
				JSONObject clonedJsonObject = JSON.parseObject(JSON.toJSONString(context.getJsonObject()));
				clonedJsonObject.put("alarmOBJ", context.getAlarm());
				publishAlarmAfterCommit(clonedJsonObject);
				if (context.getAlarm().getSceneType().equals(SceneTypeEnums.SCENE_TYPE_2.getKey() + "")) {
					context.getJsonObject().put("tenantId", context.getAlarm().getTenantId());
					triggerLogicAfterInsertAfterCommit(context.getJsonObject());
				}
			}
		} finally {
			AlarmShardContext.clear();
		}
	}

	private void persistPreparedAlarmSingleInTransaction(String batchId, AlarmInsertContext context) {
		Alarm alarm = context.getAlarm();
		String shardSuffix = context.getShardSuffix();
		try {
			/*
			 * 单条兜底优先复用批量 prepare 时已经分配的 shardSuffix。
			 * 如果批量事务在分片分配前失败，才重新分配，确保兜底仍能独立完成。
			 */
			if (shardSuffix != null) {
				AlarmShardContext.setTableSuffix(shardSuffix);
			} else {
				shardSuffix = prepareAlarmShard(alarm);
				context.setShardSuffix(shardSuffix);
			}
			if (context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_1.getKey()
					|| context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
				AlarmHandle alarmHandle = new AlarmHandle();
				alarmHandle.setAlarmId(alarm.getAlarmId());
				alarmHandle.setAlarmBegintime(alarm.getAlarmBegintime());
				alarmHandle.setCreateTime(DateUtils.getNowDate());
				alarmHandleMapper.insertAlarmHandle(alarmHandle);
			}
			if (!context.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())
					&& context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
				AlarmElectrolyticCell ec = context.getAlarmElectrolyticCell();
				bindElectrolyticCellToAlarm(ec, alarm, context.getJsonObject());
				iAlarmElectrolyticCellService.insertAlarmElectrolyticCell(ec);
				iAlarmElectrolyticCellService.insertAlarmElectrolyticCellEctype(ec);
				context.getJsonObject().put("alarmElectrolyticCell", ec);
			}
			if (!context.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())
					&& context.getJsonObject().getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_6.getKey()) {
				AlarmPartialDischarge pd = context.getAlarmPartialDischarge();
				if (pd == null) {
					pd = buildPartialDischargeForInsert(context.getJsonObject(), alarm);
					context.setAlarmPartialDischarge(pd);
				}
				iAlarmPartialDischargeService.insertAlarmPartialDischarge(pd);
			}
			alarm.setCreateTime(DateUtils.getNowDate());
			int inserted = alarmMapper.insertAlarm(alarm);
			if (inserted <= 0) {
				throw new CustomException("鎶ヨ涓昏〃鎻掑叆杩斿洖 0锛宎larmCid=" + alarm.getAlarmCid());
			}
			if (shardSuffix != null && alarmCidIndexService != null) {
				AlarmCidRoute route = alarmCidIndexService.saveActiveHotRoute(alarm, shardSuffix);
				if (alarmStopEventService != null) {
					// 单条兜底同样要处理 stop 早到，保持和批量路径一致的数据一致性边界。
					alarmStopEventService.applyPendingStopForNewAlarm(alarm, route);
				}
			}
			// 单条兜底也必须保持旧 push payload 与 afterCommit 时机。
			JSONObject clonedJsonObject = JSON.parseObject(JSON.toJSONString(context.getJsonObject()));
			clonedJsonObject.put("alarmOBJ", alarm);
			publishAlarmAfterCommit(clonedJsonObject);
			if (alarm.getSceneType().equals(SceneTypeEnums.SCENE_TYPE_2.getKey() + "")) {
				context.getJsonObject().put("tenantId", alarm.getTenantId());
				triggerLogicAfterInsertAfterCommit(context.getJsonObject());
			}
		} finally {
			AlarmShardContext.clear();
		}
	}

	private AlarmPartialDischarge buildPartialDischargeForInsert(JSONObject jsonObject, Alarm alarm) {
		AlarmPartialDischarge alarmPartialDischarge = jsonObject.getJSONObject("pdData").toJavaObject(AlarmPartialDischarge.class);
		alarmPartialDischarge.setAlarmId(alarm.getAlarmId());
		alarmPartialDischarge.setMaxAmplitude(alarmPartialDischarge.getMaxAmplitude() / 100);
		if (StringUtils.isNotBlank(alarmPartialDischarge.getPrpdData())) {
			byte[] decodedBytes = Base64.getDecoder().decode(alarmPartialDischarge.getPrpdData());
			String filePath = uploadFile(decodedBytes, alarm.getDeviceSn() + alarmPartialDischarge.getSensorId() + ".dat");
			alarm.setPicturePath(filePath);
		}
		alarm.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_2.getDescription());
		return alarmPartialDischarge;
	}

	private List<String> sampleAlarmInsertCids(List<JSONObject> jsonObjects) {
		return jsonObjects.stream()
				.filter(Objects::nonNull)
				.map(jsonObject -> jsonObject.getString("alarmId"))
				.filter(StringUtils::isNotBlank)
				.limit(5)
				.collect(Collectors.toList());
	}

	private String buildAlarmInsertTraceId(JSONObject jsonObject) {
		String traceId = jsonObject == null ? null : jsonObject.getString("traceId");
		if (StringUtils.isBlank(traceId)) {
			traceId = UUID.randomUUID().toString().replace("-", "");
		}
		return traceId;
	}

	/**
	 * 记录报警新增阶段日志。
	 *
	 * <p>日志字段必须包含 traceId、alarmCid、deviceSn、sceneType、alarmType、tenantId、耗时和错误信息，
	 * 这样 MQ 重复消费、Redis 异常、设备缓存缺失、DB 失败都可以按同一条链路追踪。</p>
	 */
	private void logAlarmInsertStage(String traceId, String stage, String result, long startMs,
									 JSONObject jsonObject, Alarm alarm, String errorMsg) {
		log.info("alarm insert stage={}, result={}, traceId={}, alarmCid={}, deviceSn={}, sceneType={}, alarmType={}, tenantId={}, costMs={}, error={}",
				stage,
				result,
				traceId,
				jsonObject == null ? null : jsonObject.getString("alarmId"),
				jsonObject == null ? null : jsonObject.getString("deviceSn"),
				jsonObject == null ? null : jsonObject.getString("sceneType"),
				jsonObject == null ? null : jsonObject.getString("alarmType"),
				alarm == null ? null : alarm.getTenantId(),
				System.currentTimeMillis() - startMs,
				errorMsg);
	}

	@Transactional(rollbackFor = {Exception.class})
	public void insertSql(String alarmType,JSONObject jsonObject ,Alarm alarm,AlarmElectrolyticCell alarmElectrolyticCell){
		String shardSuffix = AlarmShardContext.getTableSuffix();
		if (StringUtils.isBlank(shardSuffix) || alarm.getAlarmId() == null) {
			shardSuffix = prepareAlarmShard(alarm);
		}
		try {
			try {
				if (jsonObject.getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_1.getKey() || jsonObject.getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
					AlarmHandle alarmHandle = new AlarmHandle();
					alarmHandle.setAlarmId(alarm.getAlarmId());
					alarmHandle.setAlarmBegintime(alarm.getAlarmBegintime());
					alarmHandle.setCreateTime(DateUtils.getNowDate());
					alarmHandleMapper.insertAlarmHandle(alarmHandle);

				}
			} catch (Exception e) {
				log.error("处理表插入报警报错:{}", e.getMessage());
				throw new CustomException(e.getMessage());
			}

			//电解槽关联报警
			try {
				if (!alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey()) &&
						jsonObject.getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey()) {
					bindElectrolyticCellToAlarm(alarmElectrolyticCell, alarm, jsonObject);
					iAlarmElectrolyticCellService.insertAlarmElectrolyticCell(alarmElectrolyticCell);
					iAlarmElectrolyticCellService.insertAlarmElectrolyticCellEctype(alarmElectrolyticCell);
					jsonObject.put("alarmElectrolyticCell",alarmElectrolyticCell);
				}
			} catch (Exception e) {
				log.error("电解槽插入报警报错:{}", e.getMessage());
				throw new CustomException(e.getMessage());
			}

			//在线局放报警
			if (!alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey()) &&
					jsonObject.getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_6.getKey()){
				alarm = insertPartialDischarge(jsonObject, alarm);
				//通过通道信息 在alarm表的 targetname字段进行组装
			}

			int i = 0 ;
			try {
				alarm.setCreateTime(DateUtils.getNowDate());
				i = alarmMapper.insertAlarm(alarm);
				if (i <= 0) {
					throw new CustomException("报警主表插入返回 0，alarmCid=" + alarm.getAlarmCid());
				}
				if (shardSuffix != null) {
					AlarmCidRoute route = alarmCidIndexService.saveActiveHotRoute(alarm, shardSuffix);
					if (alarmStopEventService != null) {
						alarmStopEventService.applyPendingStopForNewAlarm(alarm, route);
					}
				}

				String jsonStr = JSON.toJSONString(jsonObject);
// 2. 再将字符串反序列化为新的 JSONObject（完全独立的对象）
				JSONObject clonedJsonObject = JSON.parseObject(jsonStr);
				clonedJsonObject.put("alarmOBJ",alarm);
				publishAlarmAfterCommit(clonedJsonObject);
			} catch (Exception e) {
				log.error("报警主表插入报警报错:{}", e.getMessage());
				throw new CustomException(e.getMessage());
			}

			// 如果插入操作成功且是电解槽行业
			if (i > 0 && alarm.getSceneType().equals(SceneTypeEnums.SCENE_TYPE_2.getKey()+"")) {
				//断线报警触发
				if (alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey())) {
					// Redis dedup key is kept until TTL to guard MQ redelivery.
				}
				// 触发逻辑
				jsonObject.put("tenantId",alarm.getTenantId());

				/** 重复报警开启*/
				if (shardSuffix != null) {
					AlarmShardContext.clear();
					shardSuffix = null;
				}
				triggerLogicAfterInsertAfterCommit(jsonObject);
			}
		} finally {
			if (shardSuffix != null) {
				AlarmShardContext.clear();
			}
		}

	}

	/**
	 * 为新增报警准备分片上下文。
	 *
	 * <p>旧接口上报可能缺少 time。时间分片必须有 alarm_beginTime，否则无法确定月份；
	 * 为了保持现有接口兼容，这里在缺失时使用当前时间兜底并打印 warn。分片后缀只在当前
	 * 线程生效，insertSql 结束后会在 finally 中清理，避免污染后续请求。</p>
	 */
	private String prepareAlarmShard(Alarm alarm) {
		if (alarmCidIndexService == null) {
			return null;
		}
		if (alarm.getAlarmBegintime() == null) {
			alarm.setAlarmBegintime(DateUtils.getNowDate());
			log.warn("报警上报缺少 time 字段，已使用当前时间作为 alarm_beginTime，alarmId={}, alarmCid={}",
					alarm.getAlarmId(), alarm.getAlarmCid());
		}
		AlarmMonthlySliceTableManager.ShardAllocation allocation = alarmCidIndexService.allocate(alarm.getAlarmBegintime());
		long snowflakeSeed = alarm.getAlarmId() == null ? snowflake.nextId() : alarm.getAlarmId();
		alarm.setAlarmId(alarmCidIndexService.nextAlarmId(alarm.getAlarmBegintime(),
				allocation.getSliceNo(), allocation.getRowNo(), snowflakeSeed));
		String shardSuffix = allocation.getTableSuffix();
		AlarmShardContext.setTableSuffix(shardSuffix);
		return shardSuffix;
	}

	/**
	 * 将电解槽扩展表记录重新绑定到主报警记录。
	 *
	 * <p>电解槽信息在设备/Redis 解析阶段已经组装，但真正可作为数据库外键和分片键的
	 * alarm_id 必须以 alarm 主表生成的内部 ID 为准。批量、单条、批量失败拆单三条链路都会在入库前
	 * 调用这里做最后一次覆盖，边界是只同步主表身份字段和本次上报的 irmsSn，不重新读取 Redis，
	 * 也不改变 MQ 消息体、push payload 或电解槽业务字段。</p>
	 */
	private void bindElectrolyticCellToAlarm(AlarmElectrolyticCell alarmElectrolyticCell, Alarm alarm, JSONObject jsonObject) {
		if (alarmElectrolyticCell == null || alarm == null) {
			throw new IllegalStateException("Electrolytic cell alarm binding is missing alarm or extension object, alarmCid="
					+ (jsonObject == null ? null : jsonObject.getString("alarmId")));
		}
		alarmElectrolyticCell.setAlarmId(alarm.getAlarmId());
		alarmElectrolyticCell.setAlarmBegintime(alarm.getAlarmBegintime());
		alarmElectrolyticCell.setCreateTime(DateUtils.getNowDate());
		if (jsonObject != null && StringUtils.isNotBlank(jsonObject.getString("irmsSn"))) {
			alarmElectrolyticCell.setIrmsSn(jsonObject.getString("irmsSn"));
		}
		assertAlarmShardKeys(alarm, alarmElectrolyticCell, jsonObject);
	}

	private void assertAlarmShardKeys(Alarm alarm, AlarmElectrolyticCell alarmElectrolyticCell, JSONObject jsonObject) {
		if (alarm == null || alarm.getAlarmId() == null || alarm.getAlarmBegintime() == null) {
			throw new IllegalStateException("Alarm shard keys must be present before insert, alarmCid="
					+ (jsonObject == null ? null : jsonObject.getString("alarmId"))
					+ ", alarmId=" + (alarm == null ? null : alarm.getAlarmId())
					+ ", alarmBegintime=" + (alarm == null ? null : alarm.getAlarmBegintime()));
		}
		if (alarmElectrolyticCell == null
				|| alarmElectrolyticCell.getAlarmId() == null
				|| alarmElectrolyticCell.getAlarmBegintime() == null) {
			throw new IllegalStateException("Electrolytic cell shard keys must be present before insert, alarmCid="
					+ (jsonObject == null ? null : jsonObject.getString("alarmId"))
					+ ", alarmId=" + (alarmElectrolyticCell == null ? null : alarmElectrolyticCell.getAlarmId())
					+ ", alarmBegintime=" + (alarmElectrolyticCell == null ? null : alarmElectrolyticCell.getAlarmBegintime()));
		}
	}


	void jsonTransformJava (JSONObject jsonObject,Alarm alarm) throws ParseException {
		StringBuilder sb = new StringBuilder();
		if (StringUtils.isNotBlank(jsonObject.getString("alarmDegree"))) {
			//报警等级
			alarm.setAlarmRank(jsonObject.getString("alarmDegree"));
		}
		if (jsonObject.containsKey("gatewaySn") && StringUtils.isNotBlank(jsonObject.getString("gatewaySn"))) {
			alarm.setIrmsSn(jsonObject.getString("gatewaySn"));
		}
		if (jsonObject.containsKey("irmsSn") && StringUtils.isNotBlank(jsonObject.getString("irmsSn"))) {
			alarm.setIrmsSn(jsonObject.getString("irmsSn"));
		}
		if (jsonObject.containsKey("time") && StringUtils.isNotBlank(jsonObject.getString("time"))) {
			alarm.setAlarmBegintime(DateUtils.parseDate(jsonObject.getString("time"), DateUtils.YYYY_MM_DD_HH_MM_SS));
		}

		if (jsonObject.containsKey("areaSn") && StringUtils.isNotBlank(jsonObject.getString("areaSn"))) {
			alarm.setAreaSn(jsonObject.getString("areaSn"));
		}
		if (jsonObject.containsKey("maxTemp") && StringUtils.isNotBlank(jsonObject.getString("maxTemp"))) {
			alarm.setMaxTemp(jsonObject.getString("maxTemp"));
		}
		if (jsonObject.containsKey("minTemp") && StringUtils.isNotBlank(jsonObject.getString("minTemp"))) {
			alarm.setMinTemp(jsonObject.getString("minTemp"));
		}
		if (jsonObject.containsKey("irPic") && StringUtils.isNotBlank(jsonObject.getString("irPic"))) {
			sb.append(jsonObject.getString("irPic")+";");
		}
		if (jsonObject.containsKey("ccdPic") && StringUtils.isNotBlank(jsonObject.getString("ccdPic"))) {
			sb.append(jsonObject.getString("ccdPic")+";");
		}
		alarm.setPicturePath(sb.toString());
	}


	public void breakLineUnitAction(String deviceSn){
		TmActionDto tmActionDto = new TmActionDto();
		tmActionDto.setDeviceSn(deviceSn);
		if (isRemoteCallStubEnabled()) {
			// 本地压测只验证 alarm 内部入库链路，跨服务联动到调用点即停止，避免 hpis-tmAnalysis 未启动导致 MQ 重投。
			logRemoteCallStub("RemoteTmActionService.unitAction", tmActionDto);
			return;
		}
		remoteTmActionService.unitAction(tmActionDto);
	}

	/**
	 * 断线报警
	 * @param cameraType
	 * @param device
	 * @param alarm
	 * @return
	 */
	public Alarm breakLine(String cameraType, DeviceKeyInfoDTO device, Alarm alarm) {
		//2024/11/13根据不同的断线报警类型调用目标方法
		if (IrTypeEnums.ITEMS_10.getKey().equals(cameraType)) {
			//温度传感器掉线
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("deviceSn", device.getDeviceSn());
			jsonObject.put("isActive", UserStatus.DISABLE.getCode());
			if (isRemoteCallStubEnabled()) {
				// 断线 start 只打印设备离线同步 payload，不真正请求设备服务，保障本地 MQ->DB 压测不依赖 hpis-device。
				logRemoteCallStub("RemoteTmService.alarmTmOffLine", jsonObject);
				return alarm;
			}
			remoteTmService.alarmTmOffLine(jsonObject);
		} else {
			//红外通道sn规则（deviceSn）
			JSONObject jsonObject = new JSONObject();
			jsonObject.put("deviceSn", device.getDeviceSn());
			jsonObject.put("isActive", UserStatus.DISABLE.getCode());
			//26.3.25将视频类型传过去
			jsonObject.put("cameraType", cameraType);
			if (isRemoteCallStubEnabled()) {
				// 断线 start 的红外通道离线同步只打日志；真实状态同步留给完整联调环境验证。
				logRemoteCallStub("RemoteIrChannelService.alarmIrOffLine", jsonObject);
				return alarm;
			}
			remoteIrChannelService.alarmIrOffLine(jsonObject);
		}
		return alarm;
	}

	   	Alarm	insertPartialDischarge(JSONObject jsonObject ,Alarm alarm){
		AlarmPartialDischarge alarmPartialDischarge = jsonObject.getJSONObject("pdData").toJavaObject(AlarmPartialDischarge.class);
		alarmPartialDischarge.setAlarmId(alarm.getAlarmId());
		alarmPartialDischarge.setMaxAmplitude(alarmPartialDischarge.getMaxAmplitude()/100);
		//存储报警时的prpd图
		if(StringUtils.isNotBlank(alarmPartialDischarge.getPrpdData())){
			byte[] decodedBytes = Base64.getDecoder().decode(alarmPartialDischarge.getPrpdData());
			String filePath = uploadFile(decodedBytes, alarm.getDeviceSn() +  alarmPartialDischarge.getSensorId()+".dat");
			alarm.setPicturePath(filePath);
		};
		   iAlarmPartialDischargeService.insertAlarmPartialDischarge(alarmPartialDischarge);
		alarm.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_2.getDescription());
//		alarm.setTargetName(channelName+"-"+ alarmPartialDischarge.getSensorId());
		return alarm;
		}

	/**
	 * 报警处理表（对一般报警和断线报警进行处理）
	 * @param alarm
	 * @param jsonObject
	 */
	Alarm insetAlarmHandle(Alarm alarm,JSONObject jsonObject,DeviceKeyInfoDTO device) {
		if (!alarm.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription()) && !alarm.getSceneType().equals(SceneTypeEnums.SCENE_TYPE_2.getKey() + "") ) {
		//不是断线报警 记录  只处理一般行业的非断线报警
			if (jsonObject.containsKey("srcSceneType") && SceneTypeEnums.SCENE_TYPE_11.getKey() == jsonObject.getIntValue("srcSceneType")) {

				if (alarm.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getDescription())) {
					DUnitInfoDto dUnitInfoDto = new DUnitInfoDto();
					String[] split = jsonObject.getString("deviceSn").split(",");
					dUnitInfoDto.setTmSns(split[0]);
					if (isRemoteCallStubEnabled()) {
						// 单独压测 alarm 时不查询单元服务，使用设备缓存名称兜底，保证处理表仍能落库。
						logRemoteCallStub("RemoteDUnitInfoService.unitInfo", dUnitInfoDto);
						alarm.setTargetName(device.getDeviceName());
						return alarm;
					}
					R<DUnitInfoDto> dUnitInfoDtoR = remoteDUnitInfoService.unitInfo(dUnitInfoDto);
					if (R.FAIL == dUnitInfoDtoR.getCode()) {
						throw new BaseException(dUnitInfoDtoR.getMsg());
					}
					alarm.setTargetName(dUnitInfoDtoR.getData().getUnitName());
				}else if (alarm.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_7.getKey())){
					String tubeColor = "";
					if (jsonObject.containsKey("colorTubeName") && StringUtils.isNotBlank(jsonObject.getString("colorTubeName"))) {
						tubeColor = jsonObject.getString("colorTubeName");
					}
					alarm.setTargetName(device.getDeviceName()+"_"+tubeColor);
				}

			}else {
				if (isRemoteCallStubEnabled()) {
					/*
					 * 这里是当前 smoke 阻断点：非断线报警需要 hpis-device 查询 area/irms 关系。
					 * 测试模式下不请求远程服务，按设备缓存名称和 areaSn 组装一个可入库的目标名；
					 * 该兜底值只服务 MQ、分片和 DB 压测，不代表生产业务展示名称。
					 */
					JSONObject payload = new JSONObject();
					payload.put("deviceSn", jsonObject.getString("deviceSn"));
					payload.put("areaSn", jsonObject.getString("areaSn"));
					payload.put("irmsSn", jsonObject.getString("irmsSn"));
					logRemoteCallStub("RemoteDeviceAreaService.selectDeviceAreaByAreaSnAndIrmsSn", payload);
					String areaName = StringUtils.isNotBlank(jsonObject.getString("areaSn"))
							? jsonObject.getString("areaSn")
							: "stub-area";
					alarm.setTargetName(device.getDeviceName() + "_" + areaName);
					return alarm;
				}
				R<List<DeviceAreaKeyInfoDTO>> listR = remoteDeviceAreaService.selectDeviceAreaByAreaSnAndIrmsSn(jsonObject.getString("deviceSn"), jsonObject.getString("areaSn"), jsonObject.getString("irmsSn"));
				if (R.FAIL == listR.getCode()) {
					throw new BaseException(listR.getMsg());
				}

				if ( listR.getData()!=null && listR.getData().size()>0 &&listR.getData().get(0) != null) {
					DeviceAreaKeyInfoDTO deviceAreaKeyInfoDTO = listR.getData().get(0);
					StringBuilder sb = new StringBuilder();
					sb.append(deviceAreaKeyInfoDTO.getTargetDeviceName() + "_" + deviceAreaKeyInfoDTO.getAreaName());
					alarm.setTargetName(sb.toString());
				}
			}
		}
		return  alarm;
	}

	/**
	 * 电解槽报警 详情表插入
	 * @param jsonObject
	 * @param alarm
	 * @return
	 * @throws InterruptedException
	 */
	AlarmElectrolyticCell insetElectrolyticCell(JSONObject jsonObject,Alarm alarm) {
		validateElectrolyticCellStartPayload(jsonObject);
		//观察位置
		String type = jsonObject.getString("type");
		int subdivideIndex = jsonObject.getIntValue("subdivideIndex");
		AlarmElectrolyticCell alarmElectrolyticCell = new AlarmElectrolyticCell();
		//系列关键信息缓存 irmsn+seqName
		String sequenceKey = Constants.ELE_CELL_SEQUENCE_KEY + jsonObject.getString("irmsSn") + jsonObject.getString("seq");
		String sequenceStr = redisService.getCacheObject(sequenceKey);
		if (StringUtils.isBlank(sequenceStr)) {
			throw invalidElectrolyticCell(jsonObject, "missing redis sequence cache, key=" + sequenceKey);
		}
		JSONObject sequenceJson;
		try {
			sequenceJson = JSONObject.parseObject(sequenceStr);
		} catch (Exception ex) {
			throw invalidElectrolyticCell(jsonObject, "invalid redis sequence cache json, key=" + sequenceKey);
		}
		if (sequenceJson == null || StringUtils.isBlank(sequenceJson.getString("sequenceId"))) {
			throw invalidElectrolyticCell(jsonObject, "missing sequenceId in redis sequence cache, key=" + sequenceKey);
		}
		if (StringUtils.isBlank(sequenceJson.getString("firstElectrodesPolarity"))) {
			throw invalidElectrolyticCell(jsonObject, "missing firstElectrodesPolarity in redis sequence cache, key=" + sequenceKey);
		}
		if (type.equals(ObservationPlaceEnum.PLACE_ENUM_2.getKey())) {
			alarmElectrolyticCell.setBusBarsNumber(subdivideIndex);
		} else if (type.equals(ObservationPlaceEnum.PLACE_ENUM_1.getKey())) {
			alarmElectrolyticCell.setElectrodesNumber(subdivideIndex);
		}
		alarmElectrolyticCell.setSequenceId(sequenceJson.getString("sequenceId"));
		// 这里读取到的 alarm_id 已经由 prepareAlarmShard 提前生成；入库前还会再次绑定，防止未来调用方漏掉前置生成。
		alarmElectrolyticCell.setAlarmId(alarm.getAlarmId());
		alarmElectrolyticCell.setRowIndex(jsonObject.getInteger("kua"));
		alarmElectrolyticCell.setGrooveNumber(jsonObject.getInteger("grooveIndex"));
		alarmElectrolyticCell.setObservationPlace(type);
		alarmElectrolyticCell.setSubdivideNumber(subdivideIndex);
		alarmElectrolyticCell.setTemperatureVariation(new BigDecimal(jsonObject.getString("maxTemp")));
		String electrodesPolarity = ElecCellPackMerNameUtil.estimateElectrodesPolarity(sequenceJson.getString("firstElectrodesPolarity"), subdivideIndex);
		alarm.setTargetName(ElecCellPackMerNameUtil.packMerName(jsonObject.getString("seq"), subdivideIndex, type,
				alarmElectrolyticCell.getGrooveNumber(), alarmElectrolyticCell.getRowIndex(), electrodesPolarity));
		//24.5.13插入电解槽点位副表
		//24.7.1新增设备id字段
		alarmElectrolyticCell.setDeviceSn(alarm.getDeviceSn());
		alarmElectrolyticCell.setIrmsSn(jsonObject.getString("irmsSn"));
		return alarmElectrolyticCell;
	}

	private void validateElectrolyticCellStartPayload(JSONObject jsonObject) {
		if (jsonObject == null) {
			throw new AlarmElectrolyticCellInvalidException(null, null, null, null, "payload is null");
		}
		requireElectrolyticField(jsonObject, "irmsSn");
		requireElectrolyticField(jsonObject, "seq");
		requireElectrolyticField(jsonObject, "type");
		requireElectrolyticField(jsonObject, "subdivideIndex");
		requireElectrolyticField(jsonObject, "kua");
		requireElectrolyticField(jsonObject, "grooveIndex");
		requireElectrolyticField(jsonObject, "maxTemp");
		try {
			new BigDecimal(jsonObject.getString("maxTemp"));
		} catch (NumberFormatException ex) {
			throw invalidElectrolyticCell(jsonObject, "maxTemp is not a number");
		}
	}

	private void requireElectrolyticField(JSONObject jsonObject, String fieldName) {
		if (!jsonObject.containsKey(fieldName) || StringUtils.isBlank(jsonObject.getString(fieldName))) {
			throw invalidElectrolyticCell(jsonObject, "missing field " + fieldName);
		}
	}

	private AlarmElectrolyticCellInvalidException invalidElectrolyticCell(JSONObject jsonObject, String reason) {
		if (jsonObject == null) {
			return new AlarmElectrolyticCellInvalidException(null, null, null, null, reason);
		}
		return new AlarmElectrolyticCellInvalidException(
				jsonObject.getString("alarmId"),
				jsonObject.getString("deviceSn"),
				jsonObject.getString("irmsSn"),
				jsonObject.getString("seq"),
				reason);
	}

	/**
	 * 在新增报警事务真正提交后再执行重复报警逻辑。
	 *
	 * <p>写入 alarm/alarm_handle/alarm_electrolytic_cell 时会通过 AlarmShardContext 固定本次写入分片。
	 * 重复报警判断需要查询历史报警，历史数据可能位于其他月份或同月其他容量子表，因此不能复用
	 * 本次写入的 ThreadLocal 后缀。这里通过事务同步回调把重复报警逻辑延后到提交之后执行，既避免
	 * 事务未提交时查不到新报警，也避免 ThreadLocal 分片上下文污染历史查询。</p>
	 */
	private void triggerLogicAfterInsertAfterCommit(JSONObject jsonObject) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
				@Override
				public void afterCommit() {
					triggerLogicAfterInsert(jsonObject);
				}
			});
			return;
		}
		triggerLogicAfterInsert(jsonObject);
	}

	private void publishAlarmAfterCommit(JSONObject jsonObject) {
		if (!pushOpen) {
			return;
		}
		Runnable pushTask = () -> {
			try {
				pushAlarmToPushService(jsonObject);
			} catch (Exception ex) {
				log.error("报警入库已提交，但推送报警消息失败，alarmId={}, error={}",
						jsonObject.getObject("alarmOBJ", Alarm.class) == null ? null
								: jsonObject.getObject("alarmOBJ", Alarm.class).getAlarmId(),
						ex.getMessage(), ex);
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
				@Override
				public void afterCommit() {
					threadPoolExecutor.execute(pushTask);
				}
			});
			return;
		}
		threadPoolExecutor.execute(pushTask);
	}


/**
 * 电解槽报警的重复报警判断
 */
	public void triggerLogicAfterInsert(JSONObject jsonObject) {
		// 在事务提交后触发的逻辑
		String alarmType = jsonObject.getString("alarmType");
		// 判断类型取线程(仅限于 电解槽 且 非断线报警)
		if(!alarmType.equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey()) &&
				jsonObject.getIntValue("sceneType") == SceneTypeEnums.SCENE_TYPE_2.getKey())
		{
			threadPoolExecutor.execute(() -> {
				// 另一个线程池中执行的逻辑
				//获取redis 里面 客户的相关行业的报警配置
				List<AlarmConfigure> customerAlarmConfigures = redisService.getCacheObject(Constants.ALARM_CONFIG + "-" + jsonObject.getString("tenantId") + "-" + SceneTypeEnums.SCENE_TYPE_2.getKey());

				try {
					//缓存获取设备的报警配置
					AlarmConfigure deviceAlarmConfigure = new AlarmConfigure();
					String deviceSn = jsonObject.getString("deviceSn");
				if(customerAlarmConfigures == null){
					//redis没有就去查
					deviceAlarmConfigure.setTenantId(jsonObject.getLong("tenantId"));
					List<AlarmConfigure> longAlarmConfigureMap1 = iAlarmConfigureService.selectDeviceConfigureByCustomer(deviceAlarmConfigure);
					if(longAlarmConfigureMap1.isEmpty()){
						return;
					}
					customerAlarmConfigures = longAlarmConfigureMap1;
					redisService.setCacheObject(Constants.ALARM_CONFIG + "-" + jsonObject.getString("tenantId") + "-" + SceneTypeEnums.SCENE_TYPE_2.getKey(),longAlarmConfigureMap1);
				}
				/* ——————————————有风险区域————————————————*/
					//   目前查询的是该客户下电解槽 报警配置的 第一个 按道理需要有重复报警的详细分类
					Optional<AlarmConfigure> deviceAlarmConfigureOptional = customerAlarmConfigures.stream()
							.filter(alarmConfigure ->
									// 先判断集合不为空，再contains
									alarmConfigure.getDeviceSet() != null
											&& alarmConfigure.getDeviceSet().contains(deviceSn)
							)
							.findFirst();
				/* -------------------有风险区域--------------*/

					if (deviceAlarmConfigureOptional.isPresent()) {
						deviceAlarmConfigure = deviceAlarmConfigureOptional.get();
					}

					if (deviceAlarmConfigure.getAlarmConfigureId()!=null){
						//获取配置时间
						int repeatAlarmDuration = deviceAlarmConfigure.getRepeatAlarmDuration();
						int repeatCycleNumber = deviceAlarmConfigure.getRepeatCycleNumber();
						AlarmElectrolyticCell alarmElectrolyticCell = (AlarmElectrolyticCell) jsonObject.get("alarmElectrolyticCell");
						//查询重复
						Date time = DateUtils.parseDate(jsonObject.getString("time"), DateUtils.YYYY_MM_DD_HH_MM_SS);

						//计算所需查询周期
						alarmElectrolyticCell.setEndDate(time);
						alarmElectrolyticCell.setStartDate(DateUtils.addMinutes(time, -1*repeatAlarmDuration*repeatCycleNumber));
						//处理表和电解槽详情表关联 查询点位最近报警

						RepeatAlarmDto repeatAlarmDto = iAlarmElectrolyticCellService.selectRepeatAlarmHandleByPt(alarmElectrolyticCell);
//						System.out.println(repeatAlarmDto);
						if (repeatAlarmDto!=null ){
							log.info("repeatAlarmDto：重复报警信息{},{}",repeatAlarmDto,alarmElectrolyticCell);
							Boolean handleTime = false;
							if(repeatAlarmDto.getHandleTime() !=null) {

								//判断在重复报警时间内时候处理
								handleTime = repeatAlarmDto.getHandleTime().compareTo(alarmElectrolyticCell.getStartDate()) >= 0 && repeatAlarmDto.getHandleTime().compareTo(alarmElectrolyticCell.getEndDate()) <= 0;
							}

							//判定新报警是否在上次处理的重复报警时间内
							if(handleTime) {
								//如果是重复报警讲上一条报警的重复次数置为0 时间置为空
								AlarmElectrolyticCell alarmElectrolyticCell1 = new AlarmElectrolyticCell();
								AlarmElectrolyticCell alarmElectrolyticCell2 = new AlarmElectrolyticCell();
								SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
								String handeleTime = dateFormat.format(repeatAlarmDto.getHandleTime());
								if(repeatAlarmDto.getRepeatNumber()<2) {
									alarmElectrolyticCell1.setRepeatNumber(0);
									alarmElectrolyticCell1.setRepeatTime("");
									alarmElectrolyticCell1.setRepeatHandlerUsers("");
									alarmElectrolyticCell1.setRepeatHandleTime("");
									alarmElectrolyticCell1.setAlarmId(repeatAlarmDto.getAlarmId());
									iAlarmElectrolyticCellService.updateAlarmElectrolyticCell(alarmElectrolyticCell1);

									//原报警类型改回
									Alarm alarm1 = new Alarm();
									alarm1.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getDescription());
									alarm1.setAlarmId(repeatAlarmDto.getAlarmId());
									alarmMapper.updateAlarm(alarm1);

									alarmElectrolyticCell2.setRepeatNumber(repeatAlarmDto.getRepeatNumber() + 1);

									if (repeatAlarmDto.getRepeatNumber()==0){
										alarmElectrolyticCell2.setRepeatTime(jsonObject.getString("time") );
										alarmElectrolyticCell2.setRepeatHandlerUsers(repeatAlarmDto.getHandlerName()+"");
										alarmElectrolyticCell2.setRepeatHandleTime(handeleTime);
									}else {
										alarmElectrolyticCell2.setRepeatTime(jsonObject.getString("time") + "," + repeatAlarmDto.getRepeatTime());
										alarmElectrolyticCell2.setRepeatHandlerUsers(repeatAlarmDto.getHandlerName()+","+repeatAlarmDto.getRepeatHandlerUsers());
										alarmElectrolyticCell2.setRepeatHandleTime(handeleTime+","+repeatAlarmDto.getRepeatHandleTime());
									}

								alarmElectrolyticCell2.setAlarmId(alarmElectrolyticCell.getAlarmId());
								iAlarmElectrolyticCellService.updateAlarmElectrolyticCell(alarmElectrolyticCell2);

								//当前报警类型置为重复报警
								Alarm alarm = new Alarm();
								alarm.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey());
								alarm.setAlarmId(alarmElectrolyticCell.getAlarmId());
								alarmMapper.updateAlarm(alarm);
//								log.info("上一条电解槽重复详情还原{},上一条重复主报警还原{};本条重复电解槽详情报警计数{}；本条重复主报警修改{},repeatAlarmDto{},{}",alarmElectrolyticCell1,alarm1,alarmElectrolyticCell2,alarmElectrolyticCell2,repeatAlarmDto,alarmElectrolyticCell);
								}
							}
						}
					}
				} catch (Exception e) {

					log.error("电解槽重复报警错误{},{}",e.getMessage(),customerAlarmConfigures);
				}

			});
		}
	}

//	/**
//	 * 报警推送
//	 * @param device ：deviceId,constomerId
//	 * @param alarm :封装好的报警对象
//	 */
//	public void alarmPush(DeviceKeyInfoDTO device, Alarm alarm) {
//		log.info("报警推送消息开始---------------------------------");
//		WechatAlarmData wechatAlarmData = new WechatAlarmData();
//		wechatAlarmData.setAlarmId(alarm.getAlarmId());
//		wechatAlarmData.setAlarmType(DictUtil.getDictLabelByTypeAndValue(Alarm.DICT_ALARM_TYPE, alarm.getAlarmType()));
//		wechatAlarmData.setAlarmRank(DictUtil.getDictLabelByTypeAndValue(Alarm.DICT_ALARM_RANK, alarm.getAlarmType()));
//		wechatAlarmData.setAlarmBegintime(alarm.getAlarmBegintime());
//		wechatAlarmData.setTargetName(alarm.getTargetName());
//		wechatAlarmData.setDeviceName(device.getDeviceName());
//		wechatAlarmData.setMaxTemp(alarm.getMaxTemp());
//		alarmSendService.sendRemote(device.getDeviceId(), device.getCustomerId(), alarm.getAlarmType(), wechatAlarmData);
//	}


	@Transactional(rollbackFor = Exception.class)
	@Override
	public void alarmStop(JSONObject object) {
		synchronized (object) {
			try {
				String alarmCid = object.getString("alarmId");
				Date endTime = parseRouteEndTime(object.getString("time"));
				String endTimeText = DateUtil.formatDateTime(endTime);
				AlarmCidRoute route = alarmCidIndexService == null ? null : alarmCidIndexService.findActiveRouteByCid(alarmCid);
				if (alarmCidIndexService != null && route == null) {
					AlarmCidRoute existingRoute = alarmCidIndexService.findRouteByCid(alarmCid);
					log.warn("按 cid 停止报警未找到 ACTIVE 路由，按幂等无活跃报警处理，alarmCid={}, routeExists={}",
							alarmCid, existingRoute != null);
					return;
				}
				if (route != null) {
					AlarmShardContext.setTableSuffix(route.getTableSuffix());
				}
				alarmMapper.alarmStop(alarmCid, AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey(), endTimeText);
				//断线报警更新设备通讯状态(断线报警且设备id不为空时
				Alarm alarm = alarmMapper.selectAlarmByCid(alarmCid);
				if (route != null) {
					alarmCidIndexService.closeRoute(route, endTime);
				}
				if (alarm == null) {
					log.warn("按 cid 停止报警已更新路由但未查到业务报警，alarmCid={}", alarmCid);
					return;
				}
				/**由于硝化棉 之后  断线结束会发device 同步状态  */
				if (AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription().equals(alarm.getAlarmType())) {
					if (StringUtils.equals(alarm.getTargetName(), IrTypeEnums.ITEMS_0.getDescription())) {
						JSONObject jsonObject = new JSONObject();
						jsonObject.put("deviceSn", alarm.getDeviceSn());
						jsonObject.put("cameraType", IrTypeEnums.ITEMS_0.getKey());
						jsonObject.put("isActive", UserStatus.OK.getCode());
						if (isRemoteCallStubEnabled()) {
							// 断线 stop 的设备在线恢复只记录到调用边界，避免内部压测依赖 hpis-device。
							logRemoteCallStub("RemoteIrChannelService.alarmIrOffLine", jsonObject);
						} else {
							remoteIrChannelService.alarmIrOffLine(jsonObject);
						}
					} else if (StringUtils.equals(alarm.getTargetName(), IrTypeEnums.ITEMS_1.getDescription())) {
						JSONObject jsonObject = new JSONObject();
						jsonObject.put("deviceSn", alarm.getDeviceSn());
						jsonObject.put("cameraType", IrTypeEnums.ITEMS_1.getKey());
						jsonObject.put("isActive", UserStatus.OK.getCode());
						if (isRemoteCallStubEnabled()) {
							// 断线 stop 的设备在线恢复只记录到调用边界，避免内部压测依赖 hpis-device。
							logRemoteCallStub("RemoteIrChannelService.alarmIrOffLine", jsonObject);
						} else {
							remoteIrChannelService.alarmIrOffLine(jsonObject);
						}
					} else if (StringUtils.equals(alarm.getTargetName(), IrTypeEnums.ITEMS_10.getDescription())) {
						//温度传感器掉线
						JSONObject jsonObject = new JSONObject();
						jsonObject.put("deviceSn", alarm.getDeviceSn());
						jsonObject.put("isActive", UserStatus.OK.getCode());
						if (isRemoteCallStubEnabled()) {
							// 温度传感器断线恢复同样只打日志，不真正调用外部服务。
							logRemoteCallStub("RemoteTmService.alarmTmOffLine", jsonObject);
						} else {
							remoteTmService.alarmTmOffLine(jsonObject);
						}
					}
					log.info("deviceSn={},断线报警结束", alarm.getDeviceSn());
				}
			/***********——————————————————————————————————————————————————*************/
				// 只有电解槽行业才存在电解槽扩展清理语义，避免一般行业停止报警时误触发 EC 清理。
				if (StringUtils.equals(alarm.getSceneType(), SceneTypeEnums.SCENE_TYPE_2.getKey() + "")) {
					iAlarmElectrolyticCellService.deleteAlarmElectrolyticCellEctypeById(alarm.getAlarmId());
				}
			} catch (Exception e) {
				if (e instanceof MySQLQueryInterruptedException) {
					throw new CustomException("Database operation interrupted", e);
				}
			} finally {
				AlarmShardContext.clear();
			}
		}
	}

/**
 * 根据设备sn进行批量停止
 * 撤防时或设备掉线时调用 —— 使该devicesn下的除掉线报警下的所有报警停止
 */
	@Transactional(rollbackFor = Exception.class)
	@Override
	public void alarmStopByDeviceSn(JSONObject object) {
		synchronized (object) {
			try {
				String deviceSn = object.getString("deviceSn");
				Date endTime = parseRouteEndTime(object.getString("time"));
				String endTimeText = DateUtil.formatDateTime(endTime);
				if (alarmCidIndexService != null) {
					List<AlarmCidRoute> routes = alarmCidIndexService.findActiveRoutesByDeviceSn(deviceSn);
					stopDeviceRoutesBySuffix(deviceSn, endTimeText, routes);
					alarmCidIndexService.closeRoutes(routes, endTime);
				} else {
					alarmMapper.alarmStopByDeviceId(deviceSn, endTimeText);
				}
				//24.7.1 删除电解槽副表(根据deviceId)
				iAlarmElectrolyticCellService.deleteAlarmECEctypeByDeviceId(deviceSn);

			} catch (Exception e) {
				log.error("根据设备sn批量报警停止失败 SN：{},报错原因{}",object.getString("deviceSn"),e.getMessage());
				if (e instanceof MySQLQueryInterruptedException) {
					throw new CustomException("Database operation interrupted", e);
				}
			}
		}
	}

	@Transactional(rollbackFor = Exception.class)
	@Override
	public int alarmStopByIrmsSn(JSONObject object) {
		synchronized (object) {
			try {
				String deviceSn = object.getString("irmsSn");
				Date endTime = parseRouteEndTime(object.getString("time"));
				String endTimeText = DateUtil.formatDateTime(endTime);
				if (alarmCidIndexService != null) {
					List<AlarmCidRoute> routes = alarmCidIndexService.findActiveRoutesByIrmsSn(deviceSn);
					stopIrmsRoutesBySuffix(deviceSn, endTimeText, routes);
					alarmCidIndexService.closeRoutes(routes, endTime);
				} else {
					alarmMapper.alarmAllStopByIrmsSn(deviceSn, endTimeText);
				}
				//24.7.1 删除电解槽副表(根据deviceId)
				int i = iAlarmElectrolyticCellService.deleteAlarmECEctypeByIrmsSn(deviceSn);
				return i;
			} catch (Exception e) {
				log.error("根据设备irms批量报警停止失败 irms：{},报错原因{}",object.getString("irmsSn"),e.getMessage());
				return 0;
			}
		}
	}

	private Date parseRouteEndTime(String endTimeText) {
		if (StringUtils.isBlank(endTimeText)) {
			return DateUtils.getNowDate();
		}
		try {
			return DateUtil.parse(endTimeText);
		} catch (Exception ex) {
			log.warn("报警停止时间解析失败，使用当前时间维护 cid 路由，endTime={}", endTimeText, ex);
			return DateUtils.getNowDate();
		}
	}

	private void stopDeviceRoutesBySuffix(String deviceSn, String endTime, List<AlarmCidRoute> routes) {
		Map<String, List<AlarmCidRoute>> routeGroups = groupRoutesBySuffix(routes);
		for (String tableSuffix : routeGroups.keySet()) {
			try {
				AlarmShardContext.setTableSuffix(tableSuffix);
				alarmMapper.alarmStopByDeviceId(deviceSn, endTime);
			} finally {
				AlarmShardContext.clear();
			}
		}
	}

	private void stopIrmsRoutesBySuffix(String irmsSn, String endTime, List<AlarmCidRoute> routes) {
		Map<String, List<AlarmCidRoute>> routeGroups = groupRoutesBySuffix(routes);
		for (String tableSuffix : routeGroups.keySet()) {
			try {
				AlarmShardContext.setTableSuffix(tableSuffix);
				alarmMapper.alarmAllStopByIrmsSn(irmsSn, endTime);
			} finally {
				AlarmShardContext.clear();
			}
		}
	}

	private Map<String, List<AlarmCidRoute>> groupRoutesBySuffix(List<AlarmCidRoute> routes) {
		if (routes == null || routes.isEmpty()) {
			return Collections.emptyMap();
		}
		return routes.stream()
				.filter(route -> route.getTableSuffix() != null && !"".equals(route.getTableSuffix().trim()))
				.collect(Collectors.groupingBy(AlarmCidRoute::getTableSuffix));
	}

	/**
	 * 修改【请填写功能名称】
	 *
	 * @param alarm 【请填写功能名称】
	 * @return 结果
	 */
	@Override
	public int updateAlarm(Alarm alarm)
	{
		alarm.setUpdateTime(DateUtils.getNowDate());
		return alarmMapper.updateAlarm(alarm);
	}

	/**
	 * 批量删除【请填写功能名称】
	 *
	 * @param alarmIds 需要删除的【请填写功能名称】ID
	 * @return 结果
	 */
	@Override
	public int deleteAlarmByIds(Long[] alarmIds)
	{
		return alarmMapper.deleteAlarmByIds(alarmIds);
	}

	/**
	 * 删除【请填写功能名称】信息
	 *
	 * @param alarmId 【请填写功能名称】ID
	 * @return 结果
	 */
	@Override
	public int deleteAlarmById(Long alarmId)
	{
		return alarmMapper.deleteAlarmById(alarmId);
	}

	/**
	 *
	 * @param jsonObject
	 * @return
	 */
	@Override
	public String getAlarmPictureByJSONObject(JSONObject jsonObject) {
		//实时查询报警图片
//		jsonObject.put("cmd", RequestCmdEnums.SEND_GET_ALARM_PICTURE.getCmd());

		int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
//		TransferCommandObject obj = TransferCommandObject.initializeByDevOperate(cmdSeq, dPdConfig.getDeviceSn(),
//				DeviceTypeCodeEnums.TYPE_3.getKey(), OperCodeConstants.PD_CONFIG_SET, jsonObject);
//		webSocketClient.sendMessage(obj);
		if (isRemoteCallStubEnabled()) {
			// 图片查询依赖 WebSocket 和文件服务，本地压测只验证 alarm 内部数据链路，直接返回空图片路径。
			logRemoteCallStub("WebSocketKeepAliveClient.getDataByExcptMessage(GET_ALARM_PICTURE)", jsonObject);
			return null;
		}
		JSONObject returnJson =webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(), cmdSeq);
		if (returnJson.getInteger("error") == WebsocketStatus.SUCCESS) {
			StringBuilder pathList = new StringBuilder();
			//报警红外光图片
			if (returnJson.containsKey("irPic") && StringUtils.isNotBlank(returnJson.getString("irPic"))) {
				String picture = returnJson.getString("irPic");
				//报警图片 base64 转文件 并保存其临时路径
				String path = localFilePath + UUID.randomUUID().toString().replace("-", "") + ".png";
				String fileName = UUID.randomUUID().toString().replace("-", "") + ".png";
				MultipartFile mockMultipartFile = null;
				try {
					FileConvertBase64Util.base64ToFile(picture, path);
					//取出临时文件存入文件服务并获得其路径
					File file = new File(path);
					mockMultipartFile = new MockMultipartFile(fileName, fileName, MediaType.MULTIPART_FORM_DATA_VALUE, new FileInputStream(file));
					R<SysFile> upload = remoteFileService.upload(mockMultipartFile);
					pathList.append(upload.getData().getUrl());
					FileUtils.deleteFile(path);
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}
			//报警可见光图片
			if (returnJson.containsKey("ccdPic") && StringUtils.isNotBlank(returnJson.getString("ccdPic"))) {
				String picture = returnJson.getString("ccdPic");
				//报警图片 base64 转文件 并保存其临时路径
				String path = localFilePath + UUID.randomUUID().toString().replace("-", "") + ".png";
				String fileName = UUID.randomUUID().toString().replace("-", "") + ".png";
				//取出临时文件存入文件服务并获得其路径
				MultipartFile mockMultipartFile = null;
				try {
					File file = new File(path);
					FileConvertBase64Util.base64ToFile(picture, path);
					mockMultipartFile = new MockMultipartFile(fileName, fileName, MediaType.MULTIPART_FORM_DATA_VALUE, new FileInputStream(file));
					R<SysFile> upload = remoteFileService.upload(mockMultipartFile);
					if (pathList.length() != 0) {
						pathList.append(";");
					}
					pathList.append(upload.getData().getUrl());
					FileUtils.deleteFile(path);
				} catch (IOException e) {
					log.error(e.getMessage());
				}
			}

			return pathList.toString();
		} else {
			log.error("未获取到报警图片");
		}
		return null;
	}

//	/**
//	 * 通用
//	 * @param jsonObject
//	 * @return
//	 */
//	@Override
//	public String getAlarmFileByJSONObject(JSONObject jsonObject){
//
//		//实时查询报警图片
//		jsonObject.put("cmd", RequestCmdEnums.SEND_GET_FILE.getCmd());
//		MyWebSocketClient webSocketClient = WebSocketClientBeanConfig.getWebSocketClientInstance(wsUrl);
//		webSocketClient.sendMessage(jsonObject);
//		JSONObject returnJson = webSocketClient.getExcptMessageByJson();
//		webSocketClient.close();
//		if (returnJson.getInteger("error") == WebsocketStatus.SUCCESS) {
//			StringBuilder pathList = new StringBuilder();
//			//报警红外光图片
//
//				String picture = returnJson.getString("file");
//				//报警图片 base64 转文件 并保存其临时路径
//				String path = localFilePath + UUID.randomUUID().toString().replace("-", "") + ".png";
//				String fileName = UUID.randomUUID().toString().replace("-", "") + ".png";
//				MultipartFile mockMultipartFile = null;
//				try {
//					FileConvertBase64Util.base64ToFile(picture, path);
//					//取出临时文件存入文件服务并获得其路径
//					File file = new File(path);
//					mockMultipartFile = new MockMultipartFile(fileName, fileName, MediaType.MULTIPART_FORM_DATA_VALUE, new FileInputStream(file));
//					R<SysFile> upload = remoteFileService.upload(mockMultipartFile);
//					pathList.append(upload.getData().getUrl());
//					FileUtils.deleteFile(path);
//				} catch (IOException e) {
//					log.error(e.getMessage());
//				}
//
//
//			return pathList.toString();
//		} else {
//			log.error("未获取到报警图片");
//		}
//		return null;
//
//	}

	@Override
	public String uploadFile(byte[] byteArray, String fileName) {
		if (isRemoteCallStubEnabled()) {
			// 文件上传属于跨服务副作用，测试模式只返回稳定占位路径，避免 hpis-file 未启动影响报警入库。
			JSONObject payload = new JSONObject();
			payload.put("fileName", fileName);
			payload.put("byteLength", byteArray == null ? 0 : byteArray.length);
			logRemoteCallStub("RemoteFileService.upload", payload);
			return "stub://alarm-file/" + fileName;
		}
		String filePath = localFilePath + fileName;
		//对于非常大的文件写入，FileChannel 是一个非常高效的方式。它使用了直接内存访问，可以实现较高的性能，尤其是在处理大文件时
		try (FileChannel channel = new FileOutputStream(filePath).getChannel()) {
			ByteBuffer buffer = ByteBuffer.wrap(byteArray);
			channel.write(buffer);
			buffer.clear();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		MockMultipartFile mockMultipartFile = null;
		File file;
		try {
			file = new File(filePath);
			mockMultipartFile = new MockMultipartFile(fileName, fileName, MediaType.MULTIPART_FORM_DATA_VALUE, new FileInputStream(file));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		R<SysFile> upload = remoteFileService.upload(mockMultipartFile);
		//删除旧文件
		FileUtil.del(file);
		return upload.getData().getUrl();
	}

	/**
	 * 通用
	 * @param alarmId
	 * @return
	 */
	@Override
	public Alarm getAlarmPicture(Long alarmId) {
		Alarm alarm = alarmMapper.selectAlarmById(alarmId);
		JSONObject jsonObject = new JSONObject();
		jsonObject.put("irmsSn", alarm.getIrmsSn());
		jsonObject.put("alarmId", alarm.getAlarmCid());
		String alarmPictureByJSONObject = getAlarmPictureByJSONObject(jsonObject);
		//设置图片路径 "；"拼接
		if (alarmPictureByJSONObject != null) {
			alarm.setPicturePath(alarmPictureByJSONObject);
		}
		return alarm;
	}




	/**
	 * 通用
	 * dateRange: today、sevenDays、month、year、'2023-01-01,2023-03-03'
	 * 获取温度报警次数 有空数据填充
	 * @param deviceId
	 * @param dateRange
	 * @param customerId
	 * @return
	 */
	@Override
	public List<Map<String, Object>> getDeviceAlarmCountByDeviceIdAndDateRange(String deviceId, String dateRange, String customerId) {
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		DateTimeFormatter dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
		List<Map<String, Object>> alarmCountList = new ArrayList<>();
		Map<String, Object> params = new HashMap<>();
		params.put("deviceId", deviceId);
		params.put("customerId", customerId);
		LocalDateTime targetDate = LocalDateTime.now();   // 目标日期，可以是今天或自定义日期

		String[] dates = null;
		if(dateRange.contains(",")){
			dates = dateRange.split(",");
		}

		if("today".equals(dateRange) || (dateRange.contains(",") && dates[0].equals(dates[1]))){
			if("today".equals(dateRange)){
				params.put("startTime", DateUtil.format(DateUtil.beginOfDay(DateUtil.date()), DateUtils.YYYY_MM_DD_HH_MM_SS));
				params.put("stopTime", DateUtil.format(DateUtil.endOfDay(DateUtil.date()), DateUtils.YYYY_MM_DD_HH_MM_SS));

			}
			else {
				if(dates.length == 2) {
					targetDate = LocalDate.parse(dates[0], DateTimeFormatter.ofPattern("yyyy-MM-dd")).atStartOfDay();
					params.put("startTime", dates[0] + " 00:00:00");
					params.put("stopTime", dates[1] + " 23:59:59");
				}
			}
			alarmCountList = alarmMapper.getDeviceAlarmCountByDeviceIdAndDateRangeToday(params);

			Map<String, Map<String, Long>> hourMap = new LinkedHashMap<>();
			for (int i = 0; i < 24; i++) {
				Map<String, Long> rankCountMap = new HashMap<>();
				rankCountMap.put("count", 0L);
				rankCountMap.put("rank0", 0L);
				rankCountMap.put("rank1", 0L);
				rankCountMap.put("rank2", 0L);
				hourMap.put(targetDate.withHour(i).withMinute(0).withSecond(0).format(formatter), rankCountMap);
			}

			for (Map<String, Object> item : alarmCountList) {
				Map<String, Long> rankCountMap = hourMap.get(item.get("onDate"));
				rankCountMap.put("count", (Long) item.get("count"));
				rankCountMap.put("rank0", ((BigDecimal) item.get("rank0")).longValue());
				rankCountMap.put("rank1", ((BigDecimal) item.get("rank1")).longValue());
				rankCountMap.put("rank2", ((BigDecimal) item.get("rank2")).longValue());
			}

			alarmCountList.clear();
			for (Map.Entry<String, Map<String, Long>> entry : hourMap.entrySet()) {
				Map<String, Object> map = new HashMap<>();
				map.put("onDate", entry.getKey());
				map.putAll(entry.getValue());
				alarmCountList.add(map);
			}

		} else {
			switch(dateRange) {
				case "sevenDays":
					params.put("startTime", LocalDate.now().minusDays(6).atStartOfDay().format(formatter));
					params.put("stopTime", LocalDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0).format(formatter));
					break;
				case "month":
					params.put("startTime", LocalDate.now().withDayOfMonth(1).atStartOfDay().format(formatter));
					params.put("stopTime", LocalDateTime.now().withDayOfMonth(1).plusMonths(1).minusDays(1).withHour(23).withMinute(59).withSecond(59).withNano(0).format(formatter));
					break;
				case "year":
					params.put("startTime", LocalDateTime.now().withMonth(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0).format(formatter));
					params.put("stopTime", LocalDateTime.now().withMonth(12).withDayOfMonth(31).withHour(23).withMinute(59).withSecond(59).withNano(0).format(formatter));
					break;
				default:
					if(dates.length == 2) {
						params.put("startTime", dates[0] + " 00:00:00");
						params.put("stopTime", dates[1] + " 23:59:59");
					}
					break;
			}

			alarmCountList = alarmMapper.getDeviceAlarmCountByDeviceIdAndDateRange(params);

			Map<String, Map<String, Long>> dayMap = new LinkedHashMap<>();
			LocalDate startDate = LocalDate.parse((String) params.get("startTime"), formatter);
			LocalDate endDate = LocalDate.parse((String) params.get("stopTime"), formatter);

			for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
				Map<String, Long> rankCountMap = new HashMap<>();
				rankCountMap.put("count", 0L);
				rankCountMap.put("rank0", 0L);
				rankCountMap.put("rank1", 0L);
				rankCountMap.put("rank2", 0L);
				dayMap.put(date.format(dayFormatter), rankCountMap);
			}

			for (Map<String, Object> item : alarmCountList) {
				Map<String, Long> rankCountMap = dayMap.get(item.get("onDate"));
				rankCountMap.put("count", (Long) item.get("count"));
				rankCountMap.put("rank0", ((BigDecimal) item.get("rank0")).longValue());
				rankCountMap.put("rank1", ((BigDecimal) item.get("rank1")).longValue());
				rankCountMap.put("rank2", ((BigDecimal) item.get("rank2")).longValue());
			}

			alarmCountList.clear();
			for (Map.Entry<String, Map<String, Long>> entry : dayMap.entrySet()) {
				Map<String, Object> map = new HashMap<>();
				map.put("onDate", entry.getKey());
				map.putAll(entry.getValue());
				alarmCountList.add(map);
			}
		}

		return alarmCountList;
	}

	@Override
	public String getPictureByPath(Alarm alarm) {

		JSONObject object = new JSONObject();
		object.put("irmsSn", alarm.getIrmsSn());
		object.put("path", alarm.getPicturePath());
		int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
		TransferCommandObject obj = TransferCommandObject.initializeByDevOperate(cmdSeq, alarm.getIrmsSn(), DeviceTypeCodeEnums.TYPE_1.getKey(), OperCodeConstants.IRMS_PICTURE, object);

		if (isRemoteCallStubEnabled()) {
			// 查询图片属于外部 WebSocket 链路，内部压测模式不请求设备侧，直接返回空内容。
			logRemoteCallStub("WebSocketKeepAliveClient.getPictureByPath", object);
			return null;
		}
		webSocketClient.sendMessage(obj);
		JSONObject returnJson = webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(), cmdSeq);
		String picture = returnJson.getString("file");

		return picture;
	}

	/**
	 * 通用
	 * 根据查询条件对alarm主表进行查询
	 * @param alarmQueryParameter
	 * @return
	 */
	@Override
	public List<Alarm> selectAlarmByQueryParameter(AlarmQueryParameter alarmQueryParameter){
		return alarmMapper.selectAlarmByQueryParameter(alarmQueryParameter);

	}

	/**
	 * 通用
	 * 根据用户 行业 时间 统计报警类型
	 * @param alarmQueryParameter
	 * @return
	 */
	@Override
	public  Map<String,Long> alarmModeCount(AlarmQueryParameter alarmQueryParameter){

		Date endDate = alarmQueryParameter.getEndTime();

// 使用 Calendar 设置时间为当天的最大时间
		Calendar calendar = Calendar.getInstance();
		// 将 endDate 设置到 Calendar 中
		calendar.setTime(endDate);

// 设置时间为当天的最大时间
		calendar.set(Calendar.HOUR_OF_DAY, 23);
		calendar.set(Calendar.MINUTE, 59);
		calendar.set(Calendar.SECOND, 59);
		calendar.set(Calendar.MILLISECOND, 999);

// 更新 endDate 对象
		endDate.setTime(calendar.getTimeInMillis());
		alarmQueryParameter.setEndTime(endDate);
//		List<Alarm> alarmList = alarmMapper.selectAlarmByQueryParameter(alarmQueryParameter,userInfo.getCustomerId());


		List<Map<String, Long>> maps = alarmMapper.countAlarmMode(alarmQueryParameter);

		alarmQueryParameter.setAlarmStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_0.getKey());
		List<Map<String, Long>> maps1 = alarmMapper.countNoHandelOfDay(alarmQueryParameter);

		Map<String, Long> map1 = new LinkedHashMap<>();

//
			// 输出统计结果
			if(maps.get(0)!=null) {
				map1.put("level1", maps.get(0).getOrDefault("rank0", 0L));
				map1.put("level2", maps.get(0).getOrDefault("rank1", 0L));
				map1.put("level3", maps.get(0).getOrDefault("rank2", 0L));
			}else {
				map1.put("level1",0L);
				map1.put("level2", 0L);
				map1.put("level3",0L);
			}
		if (!maps1.isEmpty()) {
			map1.put("today", maps1.get(0).getOrDefault("count_of_transactions_today", 0L));
			map1.put("lastThreeDays", maps1.get(0).getOrDefault("count_of_transactions_three_to_seven_days_ago", 0L));
			map1.put("lastWeek", maps1.get(0).getOrDefault("count_of_transactions_before_seven_days_ago", 0L));
//			map1.put("lastOneDay",countMap.getOrDefault("lastOneDay", 0L));
		}else {
			map1.put("today", 0L);
			map1.put("lastThreeDays", 0L);
			map1.put("lastWeek", 0L);
		}
		return map1;
	}

	@Override
	public  Map<YearMonth, Long>  alarmTimeCountByMonth(AlarmQueryParameter alarmQueryParameter){
		LocalDate today = LocalDate.now();
		// 今天的最大时刻是今天的最后一刻，即 23:59:59.999
		LocalDateTime todayMaxMoment = today.atTime(LocalTime.MAX);

		// 获取两个月前的第一个时刻
		LocalDate twoMonthsAgoFirst = LocalDate.now().minusMonths(2).withDayOfMonth(1);
		LocalDateTime twoMonthsAgoFirstMoment = twoMonthsAgoFirst.atStartOfDay();


		Date endDate = Date.from(todayMaxMoment.atZone(ZoneId.systemDefault()).toInstant());
		Date startDate = Date.from(twoMonthsAgoFirstMoment.atZone(ZoneId.systemDefault()).toInstant());
		alarmQueryParameter.setStartTime(startDate);
		alarmQueryParameter.setEndTime(endDate);

//		Date endDate = alarmQueryParameter.getEndTime();
//		if (endDate != null) {
//			Calendar calendar = Calendar.getInstance();
//			calendar.setTime(endDate);
//			calendar.set(Calendar.HOUR_OF_DAY, 23);
//			calendar.set(Calendar.MINUTE, 59);
//			calendar.set(Calendar.SECOND, 59);
//			calendar.set(Calendar.MILLISECOND, 999);
//
//			endDate.setTime(calendar.getTimeInMillis());
//
//		}

//		Date startDate = alarmQueryParameter.getStartTime();
//		endDate = alarmQueryParameter.getEndTime();

		List<YearMonth> expectedMonths = DateUtils.getMonthsBetweenDates(startDate, endDate);


		Map<YearMonth, Long> countByMonth = new LinkedHashMap<>();
		for (YearMonth month : expectedMonths) {
			countByMonth.put(month, 0L);
		}


		List<Alarm> dataList = selectAlarmByQueryParameter(alarmQueryParameter);



		if (dataList != null && !dataList.isEmpty()) {

			Map<YearMonth, Long> actualCounts = dataList.stream()
					.filter(data -> data.getAlarmBegintime() != null)
					.collect(Collectors.groupingBy(

							data -> {
								Date date = data.getAlarmBegintime();
								Instant instant = date.toInstant();
								LocalDateTime localDateTime = instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
								return YearMonth.from(localDateTime);
							},

							Collectors.counting()
					));


			actualCounts.forEach((month, count) -> {

				if (countByMonth.containsKey(month)) {
					countByMonth.put(month, count);
				} else {
					//查询数据不在开始结束日期的指定月份内
					log.error("Warning: Alarm date for month " + month + " is outside the query range.");
				}
			});
		}

		return countByMonth;
	}



	/**
	 * 电解槽项目
	 * 一段时间 内的今日报警 和所有报警
	 * @param alarmQueryParameter
	 * @return
	 */
	@Override
	public  Map<String,Long> alarmCountByTime(AlarmQueryParameter alarmQueryParameter) {

		Date endDate = alarmQueryParameter.getEndTime();

// 使用 Calendar 设置时间为当天的最大时间
		Calendar calendar = Calendar.getInstance();
		// 将 endDate 设置到 Calendar 中
		calendar.setTime(endDate);

// 设置时间为当天的最大时间
		calendar.set(Calendar.HOUR_OF_DAY, 23);
		calendar.set(Calendar.MINUTE, 59);
		calendar.set(Calendar.SECOND, 59);
		calendar.set(Calendar.MILLISECOND, 999);

// 更新 endDate 对象
		endDate.setTime(calendar.getTimeInMillis());
		alarmQueryParameter.setEndTime(endDate);

//		List<Alarm> alarmList = alarmMapper.selectAlarmByQueryParameter(alarmQueryParameter, userInfo.getCustomerId());


		List<Map<String, Long>> maps = alarmMapper.alarmCountByTime(alarmQueryParameter);

		Map<String, Long> map1 = new HashMap<>();
		if (maps.get(0)!=null) {
			map1.put("today", maps.get(0).get("count_today"));

			map1.put("allAlarm", maps.get(0).get("total_count_custom_range"));
		}else {
			map1.put("today", 0L);

			map1.put("allAlarm", 0L);
		}

		//统计当前报警的点位数量
		map1.put("currentAlarmCount", (long) iAlarmElectrolyticCellService.selectAlarmListByEC().size());
		return map1;
	}

	/**
	 * 通用
	 *每天报警统计（日期连续）
	 * @param alarmQueryParameter
	 * @return
	 */
	@Override
	public  Map<String, String> AlarmOfDay(AlarmQueryParameter alarmQueryParameter){
		Date endDate = alarmQueryParameter.getEndTime();

// 使用 Calendar 设置时间为当天的最大时间
		Calendar calendar = Calendar.getInstance();
		// 将 endDate 设置到 Calendar 中
		calendar.setTime(endDate);

// 设置时间为当天的最大时间
		calendar.set(Calendar.HOUR_OF_DAY, 23);
		calendar.set(Calendar.MINUTE, 59);
		calendar.set(Calendar.SECOND, 59);
		calendar.set(Calendar.MILLISECOND, 999);

// 更新 endDate 对象
		endDate.setTime(calendar.getTimeInMillis());
		alarmQueryParameter.setEndTime(endDate);


		List<Map<String, Long>> maps = alarmMapper.alarmOfDay(alarmQueryParameter);




		Map<String, String> deviceAlarmCountPerDay = new LinkedHashMap<>();

	if (maps!=null) {
		maps.forEach(map -> {

			deviceAlarmCountPerDay.put(String.valueOf(map.get("day")), map.get("count_records") + "");

		});
	}else {
		deviceAlarmCountPerDay.put(DateUtils.getDate(),"0");
	}

		return deviceAlarmCountPerDay;
	}

	@Async  // 需要开启 @EnableAsync
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void pushAlarmToPushService(JSONObject jsonObject) {
		if (!pushOpen){
			return;
		}
		if (isRemoteCallStubEnabled()) {
			// push 最终会被其他服务消费；内部压测只记录 payload，不投递到跨服务推送链路。
			logRemoteCallStub("RabbitMQAlarmPushProducer.sendCustomPushMessage", jsonObject);
			return;
		}
		// 异步执行耗时操作
		//发送json-报警 报警类型 租户 设备sn 报警区域名称 报警时间 -附加jsonObject（报警原信息）
		JSONObject pushMessage = new JSONObject();
		pushMessage.put("data",jsonObject);
		if(AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getKey().equals(jsonObject.getString("alarmType"))){
			pushMessage.put("alarmType","高温报警");
		} else if (AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getKey().equals(jsonObject.getString("alarmType"))) {
			pushMessage.put("alarmType","短线报警");
		}else {
			pushMessage.put("alarmType",AlarmTypeEnums.getValue(jsonObject.getString("alarmType")));
		}
		Alarm alarmOBJ1 = (Alarm) jsonObject.get("alarmOBJ");
		pushMessage.put("tenantId",alarmOBJ1.getTenantId());
		pushMessage.put("areaName",alarmOBJ1.getTargetName());
		pushMessage.put("time",jsonObject.getString("time"));
		pushMessage.put("deviceSn",alarmOBJ1.getDeviceSn());
		rabbitMQAlarmPushProducer.sendCustomPushMessage(pushMessage);
	}

	private boolean isRemoteCallStubEnabled() {
		return internalTestProperties != null && internalTestProperties.isRemoteCallStubEnabled();
	}

	private void logRemoteCallStub(String target, Object payload) {
		log.warn("alarm internal-test remote call stubbed, target={}, payload={}", target, JSON.toJSONString(payload));
	}

	/**
	 * 报警插入 prepared context。
	 *
	 * <p>该对象把 prepare 阶段已经完成的设备解析、报警组装、Redis 断线去重结果、分片后缀和扩展表对象保存下来。
	 * 批量事务失败后拆单兜底必须复用它，不能重新解析 rawData，否则会重复执行去重和远程副作用。</p>
	 */
	@Data
	public static class AlarmInsertContext {
		/** 单条报警链路日志 traceId，用于串起 prepare、persist、fallback 阶段。 */
		private String traceId;
		/** insert 开始时间，用于阶段耗时日志，不参与业务判断。 */
		private long insertStartMs;
		/** 原始 MQ/接口 JSON；push payload 仍基于该结构保持旧消费者兼容。 */
		private JSONObject jsonObject;
		/** 已组装好的主报警对象，批量/单条兜底都复用该对象。 */
		private Alarm alarm;
		/** 原始 alarmType key，用于判断断线、温度、电解槽扩展等分支。 */
		private String alarmType;
		/** 电解槽扩展表对象；只有电解槽非断线报警需要。 */
		private AlarmElectrolyticCell alarmElectrolyticCell;
		/** 局放扩展表对象；局放场景需要在主表外同步写入。 */
		private AlarmPartialDischarge alarmPartialDischarge;
		/** 断线 Redis 去重结果；只有最终没有成功提交时才允许 release。 */
		private DisconnectAlarmDeduplicator.DedupResult dedupResult;
		/** 已分配的分片后缀；拆单兜底优先复用，避免同一报警落到不同分片。 */
		private String shardSuffix;
		/** 标记该 context 是否已成功提交，用于控制是否释放 Redis 去重 key。 */
		private boolean insertCompleted;
		/** 重复断线报警会被标记 skipped，调用方应返回 SKIP/ack，不进入持久化。 */
		private boolean skipped;
	}
}
