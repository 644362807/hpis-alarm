package com.hpis.alarm.interceptor;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.core.toolkit.SystemClock;
import com.hpis.alarm.config.AlarmSqlLogProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.statement.ShardingPreparedStatement;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;

/**
 * 用于 Sharding 分表情况下的 SQL 日志输出。
 * 用于输出每条 SQL 语句、参数值、完整 SQL 及执行时间。
 *
 * 注意：
 * 1. PRINT_SQL_PARAM_VALUE = true 时，会打印 SQL 参数和值。
 * 2. 生产环境如果有手机号、密码、token、身份证等敏感字段，建议关闭。
 *
 * @author dss
 */
@Slf4j
@Intercepts({
		@Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
		@Signature(type = StatementHandler.class, method = "update", args = Statement.class),
		@Signature(type = StatementHandler.class, method = "batch", args = Statement.class)
})
public class CustomSqlLogInterceptor implements Interceptor {

	private final AlarmSqlLogProperties properties;

	public CustomSqlLogInterceptor() {
		this(buildLegacyProperties(false));
	}

	public CustomSqlLogInterceptor(boolean printSqlParamValue) {
		this(buildLegacyProperties(printSqlParamValue));
	}

	public CustomSqlLogInterceptor(AlarmSqlLogProperties properties) {
		this.properties = properties == null ? new AlarmSqlLogProperties() : properties;
	}

	private static AlarmSqlLogProperties buildLegacyProperties(boolean printSqlParamValue) {
		AlarmSqlLogProperties legacyProperties = new AlarmSqlLogProperties();
		legacyProperties.setEnabled(true);
		legacyProperties.setMode(AlarmSqlLogProperties.MODE_ALL);
		legacyProperties.setPrintParam(printSqlParamValue);
		return legacyProperties;
	}

	@Override
	public Object intercept(Invocation invocation) throws Throwable {
		Statement statement = unwrapStatement(invocation.getArgs()[0]);
		Object target = PluginUtils.realTarget(invocation.getTarget());
		StatementHandler statementHandler = (StatementHandler) target;
		MetaObject metaObject = SystemMetaObject.forObject(statementHandler);

		MappedStatement mappedStatement = null;
		BoundSql boundSql = null;
		try {
			mappedStatement = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
		} catch (Exception e) {
			log.warn("Get MappedStatement failed", e);
		}
		try {
			boundSql = statementHandler.getBoundSql();
		} catch (Exception e) {
			log.warn("Get BoundSql failed", e);
		}
		String mappedStatementId = mappedStatement != null ? mappedStatement.getId() : "Unknown";

		long start = SystemClock.now();
		Object result = invocation.proceed();
		long timing = SystemClock.now() - start;

		if (!shouldLog(mappedStatementId, timing)) {
			return result;
		}

		String sqlLogger = "\n\n==============  Sql Start  ==============" +
				"\nExecute ID  : {}" +
				"\n{}" +
				"\nExecute Time: {} ms" +
				"\n==============  Sql  End   ==============\n";

		log.info(sqlLogger, mappedStatementId, buildSqlLog(statement, mappedStatement, boundSql), timing);

		return result;
	}

	boolean shouldLog(String mappedStatementId, long timing) {
		if (!properties.isEnabled()) {
			return false;
		}
		String mode = properties.normalizedMode();
		if (AlarmSqlLogProperties.MODE_ALL.equals(mode)) {
			return true;
		}
		boolean slowSql = properties.isSlowEnabled()
				&& properties.getSlowMs() >= 0
				&& timing >= properties.getSlowMs()
				&& !isStopWorkerSql(mappedStatementId);
		if (AlarmSqlLogProperties.MODE_SLOW.equals(mode)) {
			return slowSql;
		}
		return isAlarmWriteSql(mappedStatementId) || slowSql;
	}

	private boolean isAlarmWriteSql(String mappedStatementId) {
		if (mappedStatementId == null || !mappedStatementId.startsWith("com.hpis.alarm.mapper.AlarmMapper.")) {
			return false;
		}
		String methodName = mappedStatementId.substring("com.hpis.alarm.mapper.AlarmMapper.".length());
		return "insert".equals(methodName)
				|| "insertAlarm".equals(methodName)
				|| "insertAlarmBatch".equals(methodName)
				|| "insertAl1armList".equals(methodName)
				|| "updateById".equals(methodName)
				|| "updateAlarm".equals(methodName)
				|| "alarmStop".equals(methodName)
				|| "alarmStopByDeviceId".equals(methodName)
				|| "alarmAllStopByIrmsSn".equals(methodName);
	}

	private boolean isStopWorkerSql(String mappedStatementId) {
		if (mappedStatementId == null) {
			return false;
		}
		return mappedStatementId.contains(".AlarmStopEventMapper.")
				|| mappedStatementId.contains(".AlarmStopSideEffectMapper.")
				|| mappedStatementId.endsWith(".AlarmMapper.batchStopByAlarmIds")
				|| mappedStatementId.endsWith(".AlarmMapper.selectAlarmByIdsForStop")
				|| mappedStatementId.endsWith(".AlarmCidIndexMapper.selectHotByCids")
				|| mappedStatementId.endsWith(".AlarmCidIndexMapper.selectStaleByCids")
				|| mappedStatementId.endsWith(".AlarmCidIndexMapper.closeHotBatch")
				|| mappedStatementId.endsWith(".AlarmCidIndexMapper.closeStaleBatch");
	}

	private String buildSqlLog(Statement statement, MappedStatement mappedStatement, BoundSql boundSql) {
		StringBuilder sqlBuilder = new StringBuilder();
		String logicSql = null;
		try {
			logicSql = extractLogicSql(statement);
		} catch (Exception e) {
			log.error("Extract ShardingSphere logic SQL failed", e);
		}

		if ((logicSql == null || logicSql.trim().isEmpty()) && boundSql != null) {
			logicSql = boundSql.getSql();
		}

		if (logicSql != null && !logicSql.trim().isEmpty()) {
			sqlBuilder.append("Logic SQL: ")
					.append(formatSql(logicSql))
					.append("\n");
		} else {
			sqlBuilder.append("Raw Statement: ")
					.append(statement)
					.append("\n");
		}

		if (properties.isPrintParam() && mappedStatement != null && boundSql != null) {
			sqlBuilder.append("Params:\n")
					.append(buildParamLog(mappedStatement, boundSql))
					.append("\n");

			sqlBuilder.append("Full SQL: ")
					.append(buildFullSql(mappedStatement, boundSql))
					.append("\n");
		}

		return sqlBuilder.toString().trim();
	}

	/**
	 * 获取原始 Statement。
	 */
	private Statement unwrapStatement(Object firstArg) {
		Statement statement;

		if (Proxy.isProxyClass(firstArg.getClass())) {
			try {
				statement = (Statement) SystemMetaObject.forObject(firstArg).getValue("h.statement");
			} catch (Exception e) {
				statement = (Statement) firstArg;
			}
		} else {
			statement = (Statement) firstArg;
		}

		try {
			MetaObject stmtMetaObj = SystemMetaObject.forObject(statement);
			statement = (Statement) stmtMetaObj.getValue("stmt.statement");
		} catch (Exception e) {
			// ignore
		}

		try {
			MetaObject stmtMetaObj = SystemMetaObject.forObject(statement);
			if (stmtMetaObj.hasGetter("delegate")) {
				statement = (Statement) stmtMetaObj.getValue("delegate");
			}
		} catch (Exception e) {
			// ignore
		}

		return statement;
	}

	/**
	 * 提取 ShardingSphere 逻辑 SQL。
	 */
	private String extractLogicSql(Statement statement) throws Exception {
		if (statement instanceof ShardingPreparedStatement) {
			ShardingPreparedStatement shardingStatement = (ShardingPreparedStatement) statement;

			Field sqlField = ShardingPreparedStatement.class.getDeclaredField("sql");
			sqlField.setAccessible(true);

			return (String) sqlField.get(shardingStatement);
		}

		return null;
	}

	/**
	 * 构建参数日志。
	 */
	private String buildParamLog(MappedStatement mappedStatement, BoundSql boundSql) {
		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();

		if (parameterMappings == null || parameterMappings.isEmpty()) {
			return "无参数";
		}

		Object parameterObject = boundSql.getParameterObject();
		Configuration configuration = mappedStatement.getConfiguration();
		TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();

		StringBuilder builder = new StringBuilder();

		int index = 1;

		for (ParameterMapping parameterMapping : parameterMappings) {
			if (parameterMapping.getMode() == ParameterMode.OUT) {
				continue;
			}

			String propertyName = parameterMapping.getProperty();

			Object value = getParameterValue(
					boundSql,
					parameterObject,
					propertyName,
					configuration,
					typeHandlerRegistry
			);

			builder.append("[")
					.append(index++)
					.append("] ")
					.append(propertyName)
					.append(" = ")
					.append(formatValue(value))
					.append("\n");
		}

		return builder.toString().trim();
	}

	/**
	 * 构建完整 SQL。
	 */
	private String buildFullSql(MappedStatement mappedStatement, BoundSql boundSql) {
		String sql = formatSql(boundSql.getSql());

		List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();

		if (parameterMappings == null || parameterMappings.isEmpty()) {
			return sql;
		}

		Object parameterObject = boundSql.getParameterObject();
		Configuration configuration = mappedStatement.getConfiguration();
		TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();

		for (ParameterMapping parameterMapping : parameterMappings) {
			if (parameterMapping.getMode() == ParameterMode.OUT) {
				continue;
			}

			String propertyName = parameterMapping.getProperty();

			Object value = getParameterValue(
					boundSql,
					parameterObject,
					propertyName,
					configuration,
					typeHandlerRegistry
			);

			sql = sql.replaceFirst("\\?", Matcher.quoteReplacement(formatValue(value)));
		}

		return sql;
	}

	/**
	 * 获取 MyBatis 参数值。
	 */
	private Object getParameterValue(BoundSql boundSql,
									 Object parameterObject,
									 String propertyName,
									 Configuration configuration,
									 TypeHandlerRegistry typeHandlerRegistry) {
		if (boundSql.hasAdditionalParameter(propertyName)) {
			return boundSql.getAdditionalParameter(propertyName);
		}

		if (parameterObject == null) {
			return null;
		}

		if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
			return parameterObject;
		}

		try {
			MetaObject metaObject = configuration.newMetaObject(parameterObject);

			if (metaObject.hasGetter(propertyName)) {
				return metaObject.getValue(propertyName);
			}
		} catch (Exception e) {
			log.warn("Get SQL param value failed, propertyName={}", propertyName, e);
		}

		return null;
	}

	/**
	 * 格式化 SQL。
	 */
	private String formatSql(String sql) {
		if (sql == null) {
			return "";
		}

		return sql.replaceAll("[\\s]+", " ").trim();
	}

	/**
	 * 格式化参数值。
	 */
	private String formatValue(Object value) {
		if (value == null) {
			return "NULL";
		}

		if (value instanceof Number) {
			return value.toString();
		}

		if (value instanceof Boolean) {
			return value.toString();
		}

		if (value instanceof LocalDateTime) {
			return "'" + value.toString().replace("T", " ") + "'";
		}

		if (value instanceof LocalDate) {
			return "'" + value + "'";
		}

		if (value instanceof LocalTime) {
			return "'" + value + "'";
		}

		if (value instanceof Date) {
			return "'" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value) + "'";
		}

		if (value instanceof Enum<?>) {
			return "'" + ((Enum<?>) value).name().replace("'", "''") + "'";
		}

		return "'" + value.toString().replace("'", "''") + "'";
	}

	@Override
	public Object plugin(Object target) {
		if (target instanceof StatementHandler) {
			return Plugin.wrap(target, this);
		}
		return target;
	}

	/**
	 * 获取此方法名的具体 Method。
	 *
	 * @param clazz      class 对象
	 * @param methodName 方法名
	 * @return 方法
	 */
	private Method getMethodRegular(Class<?> clazz, String methodName) {
		if (Object.class.equals(clazz)) {
			return null;
		}

		for (Method method : clazz.getDeclaredMethods()) {
			if (method.getName().equals(methodName)) {
				return method;
			}
		}

		return getMethodRegular(clazz.getSuperclass(), methodName);
	}

	/**
	 * 获取 SQL 语句开头部分。
	 *
	 * @param sql ignore
	 * @return ignore
	 */
	private int indexOfSqlStart(String sql) {
		String upperCaseSql = sql.toUpperCase();

		Set<Integer> set = new HashSet<>();
		set.add(upperCaseSql.indexOf("SELECT "));
		set.add(upperCaseSql.indexOf("UPDATE "));
		set.add(upperCaseSql.indexOf("INSERT "));
		set.add(upperCaseSql.indexOf("DELETE "));
		set.remove(-1);

		if (CollectionUtils.isEmpty(set)) {
			return -1;
		}

		List<Integer> list = new ArrayList<>(set);
		list.sort(Comparator.naturalOrder());

		return list.get(0);
	}
}
