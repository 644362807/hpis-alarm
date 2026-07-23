# Alarm / Push 运行配置与数据库同步说明（2026-07-15）

## 1. 文档目标与边界

本文是当前 `hpis-alarm`、`hpis-push` 的部署运行手册，用于指导：

- Nacos 配置如何分层、放在哪个 Data ID；
- Alarm、Push 的完整启动配置如何填写；
- Java 8 下如何构建、启动和检查服务；
- 全新环境与历史环境如何按正确顺序同步 SQL；
- 如何验证“报警配置 → 报警入库 → Push 路由 → 最终通道接收”闭环。

当前边界：

- Alarm 默认端口 `8806`，Push 默认端口 `8812`。
- 配置数据的新增、查询、修改、删除必须走 Alarm/Push 接口，禁止用 SQL 代替配置 API。
- SQL 只用于基础库导入、结构迁移、历史业务数据迁移和只读核验。
- 本轮不新增 Push DDL；全新 Push 库必须导入正式数据库基线，不能根据 Java 实体临时造表。
- 企业微信、接收组、工单转派推送和候选负责人接口已进入当前版本；可选 `pushBindingId` 仍属于后续迭代，不混入本次 SQL。

## 2. 运行依赖与版本

| 依赖 | 当前要求 | 关注点 |
|---|---|---|
| JDK | Java 8 | Alarm 启动必须带 `-Dfile.encoding=UTF-8`，Push 也建议统一带上 |
| Maven | 使用父工程 Maven Reactor | 从 `D:\studyProject\hpis2.0\hpis` 执行 `-pl ... -am` |
| Nacos | 配置中心和服务注册均可用 | Data ID 后缀为 `yml`，profile 必须一致 |
| MySQL | MySQL 8.x | `hpis_alarm`、`hpis_push`、`hpis_system` 均应存在 |
| Redis | Spring Data Redis 可连接 | Alarm 使用设备/登录缓存；Push 使用配置和设备路由缓存 |
| RabbitMQ | AMQP 与管理端可用 | 需要 `alarm_queue`、`push.alarm` 和 Push 动态队列权限 |
| 设备服务 | 当前环境可注册发现 | Alarm 配置通过 `deviceIds` 解析设备并校验当前租户 |

所有密码都使用环境变量或部署平台密钥注入。本文示例中的 `${...}` 是占位符，不应替换为提交到 Git 的明文密码。

## 3. 配置加载层级

### 3.1 推荐 Data ID 划分

| 层级 | Data ID 示例 | 内容 |
|---|---|---|
| 本地引导 | 各服务 `bootstrap.yml` | 端口、服务名、profile、Nacos 地址 |
| 公共配置 | `application-prod.yml` | Redis、RabbitMQ、公共日志和 Actuator |
| Alarm 专属 | `hpis-alarm-prod.yml` | Alarm 数据源、分片、worker、推送开关 |
| Push 专属 | `hpis-push-prod.yml` | Push 数据源、线程池、动态队列参数 |

生产建议把公共连接放共享 Data ID，把不同数据库连接和业务开关放服务专属 Data ID。不要把 Alarm 的 `hpis_alarm` 数据源复制给 Push，也不要让 Push 连接 `hpis_alarm`。

Spring Cloud Alibaba 会按 `spring.application.name + profile` 加载服务专属配置；当前 `bootstrap.yml` 还显式共享：

```yaml
spring:
  cloud:
    nacos:
      config:
        shared-configs:
          - application-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
```

### 3.2 Alarm 本地 bootstrap.yml

```yaml
server:
  port: 8806

spring:
  main:
    allow-bean-definition-overriding: true
  application:
    name: hpis-alarm
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:prod}
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD}
      config:
        server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
        file-extension: yml
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD}
        context-path: /nacos
        shared-configs:
          - application-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
```

### 3.3 Push 本地 bootstrap.yml

```yaml
server:
  port: 8812

spring:
  main:
    allow-bean-definition-overriding: true
  application:
    name: hpis-push
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:prod}
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD}
      config:
        server-addr: ${NACOS_SERVER_ADDR:127.0.0.1:8848}
        file-extension: yml
        username: ${NACOS_USERNAME:nacos}
        password: ${NACOS_PASSWORD}
        context-path: /nacos
        shared-configs:
          - application-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
```

## 4. 公共配置示例：application-prod.yml

以下配置可直接作为公共 Data ID 模板。具体连接池大小必须结合实例数、数据库连接上限和压测结果调整。

```yaml
spring:
  redis:
    host: ${REDIS_HOST}
    port: ${REDIS_PORT:6379}
    database: ${REDIS_DATABASE:0}
    password: ${REDIS_PASSWORD}
    timeout: 5000ms
    lettuce:
      pool:
        min-idle: 2
        max-idle: 8
        max-active: 32
        max-wait: 3000ms

  rabbitmq:
    host: ${RABBITMQ_HOST}
    port: ${RABBITMQ_PORT:5672}
    virtual-host: ${RABBITMQ_VHOST:/}
    username: ${RABBITMQ_USERNAME}
    password: ${RABBITMQ_PASSWORD}
    connection-timeout: 10000
    requested-heartbeat: 30
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
    listener:
      simple:
        default-requeue-rejected: true
        missing-queues-fatal: false

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: when_authorized

logging:
  level:
    root: INFO
    com.hpis.alarm: INFO
    com.hpis.push: INFO
```

关注点：

- RabbitMQ 用户必须具备声明、绑定、消费和删除动态队列的权限。
- `missing-queues-fatal=false` 允许应用启动后由代码声明队列，但不代表可以忽略 RabbitMQ 连接失败。
- 不要在公共配置中关闭 Rabbit listener；仅隔离测试时用命令行覆盖 `auto-startup=false`。
- Redis 数据库编号必须与系统登录 token、设备缓存和 Push 路由使用的库一致。

## 5. Alarm 完整配置示例：hpis-alarm-prod.yml

```yaml
server:
  port: 8806

spring:
  shardingsphere:
    datasource:
      ds:
        url: jdbc:mysql://${ALARM_DB_HOST}:${ALARM_DB_PORT:3306}/hpis_alarm?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
        username: ${ALARM_DB_USERNAME}
        password: ${ALARM_DB_PASSWORD}
        driver-class-name: com.mysql.cj.jdbc.Driver

# AlarmServiceImpl 为该配置使用无默认值 @Value，必须配置且目录需要可写。
file:
  path: ${ALARM_FILE_PATH:D:/hpis-data/alarm/}

# Alarm 和 Push 的 ThreadPoolConfig 均没有字段默认值，四项必须填写。
thread:
  pool:
    core-pool-size: 20
    maximum-pool-size: 37
    keep-alive-time: 60
    work-queue-size: 15000

push:
  # Alarm 总推送开关。生产闭环开启；紧急止推时可临时关闭。
  open: true

alarm:
  push:
    # 当前推荐：未匹配当前租户报警配置时，不进入配置推送。
    require-matched-config: true

  sharding:
    # 启用 Java API 时间 + 容量分片；启用前必须完成本文章节 10 的 SQL 同步。
    enabled: true
    max-rows-per-slice: 5000000
    allocation-segment-size: 1000
    pre-create-months: 0
    include-legacy-tables: false
    id:
      # 单实例可为 0；多实例必须为每个实例分配不同的 0..255 值。
      worker-id: ${ALARM_WORKER_ID:0}
    actual-data-nodes:
      current-month-max-slice-no: 255
      next-month-max-slice-no: 9
    cid-index:
      hot-hours: 24
      stale-expire-days: 30
      cleanup-batch-size: 1000
      transfer-batch-size: 1000
      cleanup-cron: "0 0 2 * * ?"
      transfer-cron: "0 10 2 * * ?"
      stale-timeout-cron: "0 20 2 * * ?"
      log-enabled: false
    rule-refresh:
      enabled: true
      cron: "0 5 0 1 * ?"
      close-old-delay-ms: 300000
      active-on-table-created-enabled: true
      active-on-table-created-debounce-ms: 30000
      active-on-table-created-async: true
      month-end-pre-create-enabled: true
      month-end-pre-create-cron: "0 50 23 * * ?"
      month-end-pre-create-check-last-day: true
      month-end-pre-create-next-month-max-slice-no: 0

  batch:
    stop-enabled: true
    insert-enabled: true
    in-limit: 500
    fallback-single-on-batch-error: true
    fallback-single-max-items: 100
    # false 使用旧单条 Rabbit listener；灰度批量消费前先压测。
    insert-consumer-batch-enabled: false
    insert-consumer-batch-size: 100
    insert-consumer-batch-receive-timeout-ms: 50
    insert-consumer-batch-concurrency: "2-4"
    insert-consumer-batch-prefetch: 100
    insert-consumer-batch-log-enabled: false
    insert-item-profile-log-enabled: false
    stop-event-batch-enabled: true
    stop-event-upsert-batch-size: 100
    electrolytic-snapshot-batch-size: 100
    # 生产基线保持 SYNC；DUAL_WRITE/ASYNC 必须单独灰度。
    electrolytic-snapshot-mode: SYNC
    start-lock-retry-max-attempts: 3

  stop-worker:
    dispatch-enabled: true
    dispatch-interval-ms: 100
    worker-threads: 4
    claim-batch-size: 200
    max-in-flight-batches: 4
    processing-timeout-ms: 60000
    claim-recovery-batch-size: 500
    claim-recovery-interval-ms: 10000
    processing-retry-delay-ms: 1000
    claim-retry-max-attempts: 3
    claim-retry-backoff-ms: 25
    route-missing-retry-delay-ms: 5000
    route-missing-profile-log-every-batches: 100
    initial-available-delay-ms: 0
    low-watermark: 500
    high-watermark: 5000
    normal-batch-size: 500
    high-batch-size: 2000
    normal-interval-ms: 1000
    high-interval-ms: 100
    max-parallelism: 4
    side-effect-enabled: true
    applied-retention-minutes: 30
    cleanup-batch-size: 1000
    cleanup-interval-ms: 60000
    failed-retention-days: 7
    cleanup-only-low-traffic: true
    max-retry: 5
    route-missing-recovery-batch-size: 500
    route-missing-recovery-delay-ms: 1000
    route-missing-recovery-interval-ms: 10000
    idle-pause-enabled: true
    idle-confirm-count: 3
    idle-probe-interval-ms: 60000
    log-enabled: false

  ec-snapshot-worker:
    # SYNC 基线不需要派发；切到 ASYNC 前必须先灰度开启并验证积压。
    dispatch-enabled: false
    dispatch-interval-ms: 100
    worker-threads: 2
    claim-batch-size: 100
    max-in-flight-batches: 2
    processing-timeout-ms: 60000
    claim-retry-max-attempts: 3
    claim-retry-backoff-ms: 50
    retry-delay-ms: 5000
    initial-available-delay-ms: 2000
    max-retry: 5
    recovery-batch-size: 500
    recovery-interval-ms: 10000
    log-enabled: false
    idle-confirm-count: 3
    idle-probe-interval-ms: 1000

  sql-log:
    # 高流量生产默认关闭；排障时短时开启。
    enabled: false
    mode: alarm-write
    print-param: false
    slow-enabled: true
    slow-ms: 200

  dedup:
    disconnect:
      ttl-seconds: 1800

  internal-test:
    # 生产必须均为 false，否则会截断真实远程调用或 Push MQ 投递。
    remote-call-stub-enabled: false
    push-mq-stub-enabled: false

# 邮件通道使用时填写；未启用邮件推送时保持占位即可。
mail:
  domain: ${MAIL_DOMAIN:}
  from: ${MAIL_FROM:}
```

### 5.1 Alarm 配置关注点

- `thread.pool.*` 缺失会在创建线程池时产生空值问题，属于启动必配。
- `alarm.sharding.enabled=true` 时必须配置 `spring.shardingsphere.datasource.ds.*`。
- `max-rows-per-slice` 有效上限为 `8388608`；`worker-id` 有效范围为 `0..255`。
- 当前月 actualDataNodes 推荐预注册 `00..255`，下个月推荐 `00..09`。
- `alarm.batch.insert-consumer-batch-enabled=true` 会让批量 listener 接管 `alarm_queue`，开启前必须核对消费者数和数据库容量。
- `alarm.stop-worker.dispatch-enabled=false` 只暂停新认领，MQ stop 仍会落可靠事件表。
- `alarm.sql-log.enabled=true` 会增加日志 IO；`print-param=true` 还可能输出业务敏感字段。

## 6. Push 完整配置示例：hpis-push-prod.yml

```yaml
server:
  port: 8812

spring:
  datasource:
    dynamic:
      # Push 只有一个主库时仍沿用当前动态数据源约定。
      primary: master
      strict: true
      datasource:
        master:
          type: com.alibaba.druid.pool.DruidDataSource
          driver-class-name: com.mysql.cj.jdbc.Driver
          url: jdbc:mysql://${PUSH_DB_HOST}:${PUSH_DB_PORT:3306}/hpis_push?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false
          username: ${PUSH_DB_USERNAME}
          password: ${PUSH_DB_PASSWORD}
          initial-size: 5
          min-idle: 5
          max-active: 30
          max-wait: 60000

# 无代码默认值，必须配置。keep-alive-time 当前保留配置兼容位。
thread:
  pool:
    core-pool-size: 10
    maximum-pool-size: 30
    keep-alive-time: 60
    work-queue-size: 1000

queue:
  push:
    # 以下四个键由 @Value 直接读取，保持代码中的 camelCase 拼写。
    # RabbitMQ 队列最大消息数。
    maxLength: 1000
    # 队列累计消息字节上限，默认 10 MiB。
    maxLengthBytes: 10485760
    # 单条消息在动态队列中的 TTL，单位毫秒。
    messageTTL: 60000
    # 动态队列无使用后的过期时间，单位毫秒。
    expiresTime: 3600000
```

### 6.1 Push 配置关注点

- Push 启动时以数据库中 `enabled=1` 且未删除的配置为事实源，重建 Redis 路由、动态队列以及 HTTP/MQTT Consumer。
- HTTP 主动推送通道编码为 `10`，MQTT 为 `11`，被动 WebSocket 为 `1`；通道由配置 API 创建，不在 Nacos 中硬编码接收地址。
- 动态队列参数过小会丢失或过早淘汰积压消息，过大会增加 RabbitMQ 内存和磁盘压力。
- `strict=true` 可以避免数据源名称拼错后静默回落到其他库。
- Push 当前没有独立的数据库迁移文件，正式基础表必须来自受控数据库基线。

### 6.2 关键配置键精确映射

下表使用代码实际读取的完整键名，便于在 Nacos 中搜索和核对：

| 完整键名 | 服务 | 是否必配 | 说明 |
|---|---|---|---|
| `spring.shardingsphere.datasource.ds.*` | Alarm | 分片开启时必配 | Alarm 物理数据源 |
| `file.path` | Alarm | 必配 | Alarm 文件临时目录，运行账号必须可写 |
| `thread.pool.core-pool-size` | 两者 | 必配 | 公共业务线程池核心线程数 |
| `thread.pool.maximum-pool-size` | 两者 | 必配 | 公共业务线程池最大线程数 |
| `thread.pool.keep-alive-time` | 两者 | 保留 | 当前线程池创建代码尚未使用该值 |
| `thread.pool.work-queue-size` | 两者 | 必配 | 公共业务线程池等待队列大小 |
| `push.open` | Alarm | 推荐显式配置 | Alarm 推送总开关，代码默认 `false` |
| `alarm.push.require-matched-config` | Alarm | 推荐显式配置 | 当前默认 `true`，要求匹配当前租户报警配置 |
| `alarm.internal-test.remote-call-stub-enabled` | Alarm | 生产必须为 false | 是否截断跨服务调用 |
| `alarm.internal-test.push-mq-stub-enabled` | Alarm | 生产必须为 false | 是否截断 `push.alarm` MQ 投递 |
| `queue.push.maxLength` | Push | 建议显式配置 | `@Value` 直接读取，默认 `1000` |
| `queue.push.maxLengthBytes` | Push | 建议显式配置 | `@Value` 直接读取，默认 `10485760` |
| `queue.push.messageTTL` | Push | 建议显式配置 | `@Value` 直接读取，默认 `60000ms` |
| `queue.push.expiresTime` | Push | 建议显式配置 | `@Value` 直接读取，默认 `3600000ms` |

`queue.push.*` 四个参数保持表中的 camelCase 拼写，因为当前代码使用 `@Value` 的原始键名直接读取；不要仅凭 `@ConfigurationProperties` 的宽松绑定习惯改成短横线。

## 7. 本地联调覆盖配置

本地联调优先用命令行覆盖，不修改生产 Data ID：

```powershell
# 仅隔离验证 Alarm 内部分片/数据库时使用；全链路测试不能打开这两个 stub。
--alarm.internal-test.remote-call-stub-enabled=true `
--alarm.internal-test.push-mq-stub-enabled=true

# 临时停止所有 Alarm 推送。
--push.open=false

# 临时恢复“无匹配配置仍兼容推送”的旧行为。
--alarm.push.require-matched-config=false

# 只做 HTTP 接口或分片启动检查时暂停 Rabbit listener。
--spring.rabbitmq.listener.simple.auto-startup=false `
--spring.rabbitmq.listener.direct.auto-startup=false
```

注意：完整 Alarm→Push 闭环必须保持 `push.open=true`、两个 internal-test stub 均为 `false`，并启动真实 RabbitMQ listener。

## 8. 构建与启动

### 8.1 从父工程构建

```powershell
Set-Location 'D:\studyProject\hpis2.0\hpis'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk1.8.0_321'
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'

mvn -pl hpis-push -am -DskipTests package
mvn -pl hpis-alarm -am -DskipTests package
```

如果要运行 Alarm 测试，仍从父工程执行并允许依赖模块没有测试：

```powershell
mvn -pl hpis-alarm -am "-Dfile.encoding=UTF-8" -DfailIfNoTests=false test
```

### 8.2 启动顺序

固定顺序：基础依赖 → Push → Alarm → 开放报警流量。

1. 确认 Nacos、MySQL、Redis、RabbitMQ 正常。
2. 启动 Push，等待数据库启用配置恢复完成。
3. 确认 `push.alarm` 和动态队列消费者正常。
4. 启动 Alarm，等待分片 DataSource、listener 和 worker 初始化完成。
5. 完成健康检查后再恢复设备报警流量。

### 8.3 PowerShell 启动命令

先启动 Push：

```powershell
& 'C:\Program Files\Java\jdk1.8.0_321\bin\java.exe' `
  -Dfile.encoding=UTF-8 `
  -jar 'D:\studyProject\hpis2.0\hpis\hpis-push\target\hpis-push.jar' `
  --spring.profiles.active=prod
```

确认 Push 正常后启动 Alarm：

```powershell
& 'C:\Program Files\Java\jdk1.8.0_321\bin\java.exe' `
  -Dfile.encoding=UTF-8 `
  -jar 'D:\studyProject\hpis2.0\hpis\hpis-alarm\target\hpis-alarm.jar' `
  --spring.profiles.active=prod `
  --push.open=true `
  --alarm.push.require-matched-config=true
```

缺少 `-Dfile.encoding=UTF-8` 时，Java 8 可能无法正确解析 Nacos 中文 YAML，继而出现 `MalformedInputException`、数据源配置被忽略或 `sqlSessionFactory` 创建失败。

## 9. 启动检查

### 9.1 端口与进程

```powershell
Test-NetConnection 127.0.0.1 -Port 8812
Test-NetConnection 127.0.0.1 -Port 8806
Get-NetTCPConnection -State Listen | Where-Object LocalPort -In 8806,8812
```

### 9.2 健康检查

```powershell
Invoke-RestMethod 'http://127.0.0.1:8812/actuator/health'
Invoke-RestMethod 'http://127.0.0.1:8806/actuator/health'
```

如网关或安全配置拦截 Actuator，以端口、Nacos 注册实例、数据库连接和启动日志共同判断，不能只凭端口监听认定服务健康。

### 9.3 RabbitMQ 检查

```powershell
$pair = "${env:RABBITMQ_MANAGEMENT_USERNAME}:${env:RABBITMQ_MANAGEMENT_PASSWORD}"
$basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
$headers = @{ Authorization = "Basic $basic" }

Invoke-RestMethod `
  -Uri "http://${env:RABBITMQ_HOST}:15672/api/queues" `
  -Headers $headers |
  Where-Object name -In 'alarm_queue','push.alarm' |
  Select-Object name,messages,consumers
```

预期：

- Alarm 启动后 `alarm_queue` 至少有一个有效消费者；
- Push 启动后 `push.alarm` 至少有一个有效消费者；
- 已启用主动 Push 配置对应的动态队列存在并有消费者；
- 禁用或删除配置后，相应消费者和运行态路由被清理。

## 10. SQL 同步总原则

### 10.1 统一发布流程

1. 暂停上游报警流量；不能停流时至少先设置 `push.open=false`。
2. 停止 Alarm 应用，避免 DDL 与分片建表、worker、MQ 消费并发。
3. 记录当前应用版本、数据库版本、表行数和待执行脚本清单。
4. 备份 `hpis_alarm`；Push 基础库有变化时同时备份 `hpis_push`。
5. 执行结构和重复数据预检，任何阻断项都先处理，不能带错误强跑 DDL。
6. 在低峰期按本章顺序执行适用脚本。
7. 每执行一个脚本立即核验字段、索引、表和异常日志，不连续盲跑。
8. 先启动 Push、再启动 Alarm，验证完成后恢复流量和 `push.open=true`。

MySQL 命令模板：

```powershell
$mysql = 'C:\path\to\mysql.exe'
$alarmDbHost = $env:ALARM_DB_HOST
$alarmDbUser = $env:ALARM_DB_USERNAME
$sqlRoot = 'D:/studyProject/hpis2.0/hpis/hpis-alarm/src/main/resources/sql'

# -p 不在命令行写明文，客户端会交互式提示输入密码。
& $mysql -h $alarmDbHost -P 3306 -u $alarmDbUser -p `
  --default-character-set=utf8mb4 -D hpis_alarm `
  -e "source $sqlRoot/alarm-time-capacity-sharding.sql"
```

### 10.2 全新环境与历史环境分支

全新环境：

1. 先导入正式 `hpis_alarm`、`hpis_push`、`hpis_system` 基础库。
2. 执行 `alarm-time-capacity-sharding.sql`。
3. 基础脚本已经创建完整 `alarm_stop_event` 和 `alarm_electrolytic_cell_snapshot_command` 时，跳过对应的一次性迁移。
4. 按预检结果决定是否执行电解槽点位索引迁移。
5. 最后执行 `alarm-configure-workorder-migration.sql`。

历史环境：

1. 先执行基础分片脚本补齐公共表和兼容字段。
2. 有旧 `alarm_0..4` 数据时，复制并修改历史迁移模板，按“源表 + 月份”逐批迁移和核对。
3. 根据 `information_schema` 结果有条件执行 stop claim、snapshot command、点位索引迁移。
4. 所有需要保留的月度 `alarm_handle_yyyyMM_nn` 已创建后，最后执行工单迁移，使脚本能覆盖这些分片表。

禁止把目录中的所有 SQL 按文件名一次性执行。

## 11. SQL 固定顺序与关注点

| 顺序 | 脚本 | 适用条件 | 幂等性 | 主要风险 |
|---:|---|---|---|---|
| 1 | `alarm-time-capacity-sharding.sql` | 所有启用新分片的环境 | 基础对象大部分幂等 | DDL 锁；必须先关闭旧 inline 分片配置 |
| 2 | `alarm-time-capacity-sharding-migration-template.sql` | 仅存在旧 `0..4` 历史数据 | 否，模板必须复制后定参 | 重复迁移、跨月范围错误、长事务 |
| 3 | `alarm-stop-event-processing-claim-migration.sql` | 仅旧 stop 表缺 claim 字段 | 否，只能执行一次 | 重复加字段失败、ALTER 锁表 |
| 4 | `alarm-electrolytic-cell-snapshot-command-migration.sql` | snapshot command 表不存在 | 是，`CREATE TABLE IF NOT EXISTS` | 需确认字符集和索引创建成功 |
| 5 | `alarm-electrolytic-cell-ectype-point-index-migration.sql` | 点位表仍是旧主键结构 | 否，只能执行一次 | 重建主键、重复点位、NULL、长时间锁表 |
| 6 | `alarm-configure-workorder-migration.sql` | 当前报警配置/工单版本 | 主体幂等，但唯一约束有数据前提 | 重复 alarm_id 会阻断唯一索引 |

### 11.1 基础分片脚本

前置检查：确认旧 ShardingSphere `alarm_id % 5` inline 规则不会与新 Java API 分片同时启用。

```powershell
& $mysql -h $alarmDbHost -P 3306 -u $alarmDbUser -p `
  --default-character-set=utf8mb4 -D hpis_alarm `
  -e "source $sqlRoot/alarm-time-capacity-sharding.sql"
```

执行后至少检查：

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'hpis_alarm'
  AND table_name IN (
    'alarm_shard_slice','alarm_cid_index','alarm_cid_stale_index',
    'alarm_stop_event','alarm_stop_side_effect_event',
    'alarm_electrolytic_cell_snapshot_command'
  );
```

### 11.2 旧 0..4 历史数据迁移模板

该文件是模板，不是可直接全库执行的一次性脚本。每次复制后必须设置：`source_no`、月份、时间范围、slice、容量和冷热路由参数。按单个源表、单个月份执行，执行前后核对源/目标行数和 alarm_id 唯一性。

失败后不能直接重跑；先检查目标表已写入范围，清理或调整幂等条件后再继续。

### 11.3 stop PROCESSING claim 一次性迁移

先检查以下字段和索引：

```sql
SELECT column_name
FROM information_schema.columns
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_stop_event'
  AND column_name IN
      ('event_version','applied_stop_time','lock_token','locked_at','available_time');

SELECT index_name
FROM information_schema.statistics
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_stop_event'
  AND index_name IN ('idx_stop_event_claim','idx_stop_event_processing_timeout');
```

只有旧表缺少这些结构时才执行：

```powershell
& $mysql -h $alarmDbHost -P 3306 -u $alarmDbUser -p `
  --default-character-set=utf8mb4 -D hpis_alarm `
  -e "source $sqlRoot/alarm-stop-event-processing-claim-migration.sql"
```

该脚本不可重复。若中途失败，先重新查询实际已添加字段/索引，再编写只补缺失项的受控修复 SQL，不能原样重跑。

### 11.4 snapshot command 表

```powershell
& $mysql -h $alarmDbHost -P 3306 -u $alarmDbUser -p `
  --default-character-set=utf8mb4 -D hpis_alarm `
  -e "source $sqlRoot/alarm-electrolytic-cell-snapshot-command-migration.sql"
```

脚本使用 `CREATE TABLE IF NOT EXISTS`，可以重复执行；但若表已存在，脚本不会自动修正已有错误字段类型，需要另行结构对比。

### 11.5 电解槽点位主键迁移

执行前两个查询都必须返回空集：

```sql
SELECT irms_sn, sequence_id, row_index, groove_number,
       observation_place, subdivide_number, COUNT(*) AS duplicate_count
FROM hpis_alarm.alarm_electrolytic_cell_ectype
GROUP BY irms_sn, sequence_id, row_index, groove_number,
         observation_place, subdivide_number
HAVING COUNT(*) > 1;

SELECT alarm_id, irms_sn, sequence_id, row_index, groove_number,
       observation_place, subdivide_number
FROM hpis_alarm.alarm_electrolytic_cell_ectype
WHERE alarm_id IS NULL OR irms_sn IS NULL OR sequence_id IS NULL
   OR row_index IS NULL OR groove_number IS NULL
   OR observation_place IS NULL OR subdivide_number IS NULL;
```

再确认 `ectype_id` 和 `uk_ec_ectype_point` 尚不存在，然后在低峰期执行。该 ALTER 会重建主键和索引，数据量大时可能长时间锁表；必须评估表大小、磁盘临时空间和允许停机窗口。

```powershell
& $mysql -h $alarmDbHost -P 3306 -u $alarmDbUser -p `
  --default-character-set=utf8mb4 -D hpis_alarm `
  -e "source $sqlRoot/alarm-electrolytic-cell-ectype-point-index-migration.sql"
```

### 11.6 报警配置与工单迁移

添加一警一 handle 唯一约束前，所有基础表和月度表都必须先检查重复 `alarm_id`：

```sql
-- 对每个实际存在的 alarm_handle 或 alarm_handle_yyyyMM_nn 执行。
SELECT alarm_id, COUNT(*) AS duplicate_count
FROM hpis_alarm.alarm_handle
GROUP BY alarm_id
HAVING COUNT(*) > 1;
```

无重复后执行：

```powershell
& $mysql -h $alarmDbHost -P 3306 -u $alarmDbUser -p `
  --default-character-set=utf8mb4 -D hpis_alarm `
  -e "source $sqlRoot/alarm-configure-workorder-migration.sql"
```

该脚本会补齐：

- `alarm_configure.push_enabled`、`push_message_type`、`workorder_push_message_type`、`workorder_config_id`；
- `alarm_workorder` 当前事实表；
- 工单查询索引 `idx_alarm_workorder_tenant_assignee_status(tenant_id, assignee_id, status, create_time)`；
- 配置解析与设备绑定索引；
- 当前已存在 `alarm_handle` 及月度分片的 `workorder_id` 和唯一约束。

脚本检测到重复 alarm_id 会主动失败。应先由业务确认重复记录保留规则，不能为了通过迁移直接删除数据。

执行前确认同名索引不存在；执行后必须确认列顺序完整。脚本通过 `information_schema` 存储过程有条件增加该索引，可重复执行索引检查部分，但仍应遵循一次发布一次核验：

```sql
SELECT index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_workorder'
  AND index_name = 'idx_alarm_workorder_tenant_assignee_status'
ORDER BY seq_in_index;
```

预期依次返回 `tenant_id`、`assignee_id`、`status`、`create_time`。本轮不新增 `alarm_workorder.handle_picture`；完成/关闭图片继续写入 `alarm_handle.handle_picture`。DDL 执行失败时先查当前字段、索引和存储过程状态，再只补缺失项，不能假设整个脚本已事务回滚。

## 12. Push 数据库基线校验

Push 仓库没有完整基础库建表脚本。全新环境必须先导入受控的 `hpis_push` 正式基础库，不能根据 Mapper 或实体临时生成生产 DDL。仓库现有 `hpis-push/src/main/resources/sql/20260716_wecom_push_incremental.sql` 只负责企业微信增量对象，不替代基础库。

企业微信增量脚本包含无 `IF NOT EXISTS` 的建表/加列以及唯一索引迁移，不是整体幂等。只在预检确认四张企业微信表、`route_scope`、`recipient_group_id`和推送日志增量字段均未部署时整脚本执行一次；部分对象已经存在时不得重跑整文件，必须由 DBA 按 `information_schema` 结果拆分缺失项。

### 12.1 表与字段检查

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'hpis_push'
  AND LOWER(table_name) IN
      ('active_push_config','pushconfigid_devicesn','push_message_log',
       'push_wecom_app_config','push_wecom_user_binding',
       'push_recipient_group','push_recipient_group_member');

SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'hpis_push'
  AND (
    (table_name = 'active_push_config' AND column_name IN (
      'active_push_config_id','message_type','push_channel_type','enabled',
      'push_address','is_passive','tenant_id','config_name','del_flag',
      'push_key','user_id','mqtt_topic','mqtt_username','mqtt_password','mqtt_qos',
      'route_scope','recipient_group_id'
    ))
    OR (LOWER(table_name) = 'pushconfigid_devicesn'
        AND column_name IN ('active_push_config_id','device_sn'))
    OR (table_name = 'push_message_log'
        AND column_name IN ('log_id','message_id','active_push_config_id',
                            'push_channel_type','message_data','push_status',
                            'del_flag','create_time','update_time'))
    OR (table_name = 'push_wecom_app_config'
        AND column_name IN ('id','tenant_id','corp_id','agent_id',
                            'corp_secret_ciphertext','enabled','del_flag'))
    OR (table_name = 'push_wecom_user_binding'
        AND column_name IN ('id','tenant_id','user_id','wecom_user_id','enabled','del_flag'))
    OR (table_name = 'push_recipient_group'
        AND column_name IN ('id','tenant_id','group_name','enabled','del_flag'))
    OR (table_name = 'push_recipient_group_member'
        AND column_name IN ('id','tenant_id','group_id','user_id','del_flag'))
  )
ORDER BY table_name, ordinal_position;
```

Windows/MySQL 在不同 `lower_case_table_names` 设置下可能显示 `pushConfigId_deviceSn` 或全小写名称。部署时必须以正式库实际表名为准，并确认 Mapper 查询在目标操作系统可用。

### 12.2 历史数据兼容检查

```sql
SELECT del_flag, COUNT(*) AS row_count
FROM hpis_push.active_push_config
GROUP BY del_flag;

SELECT active_push_config_id, device_sn, COUNT(*) AS duplicate_count
FROM hpis_push.pushconfigid_devicesn
GROUP BY active_push_config_id, device_sn
HAVING COUNT(*) > 1;
```

当前代码兼容读取 `del_flag='0'` 和历史 `del_flag IS NULL`，新建记录显式写 `0`。本次同步不强制清洗 NULL；如需清洗，必须另立数据治理任务，先统计、备份、灰度执行。

执行企业微信增量前还必须检查 `push_message_log(message_id, active_push_config_id, target)` 是否重复。脚本遇到重复会主动终止唯一索引步骤；不得直接删除重复行，应先保留证据并确认合并规则。执行后核验 `uk_push_delivery`、四张企业微信表唯一键，以及 `active_push_config(tenant_id, recipient_group_id)` 索引。

## 13. DDL 失败处理原则

- MySQL DDL 不能依赖业务事务整体回滚；脚本失败后部分字段或索引可能已经生效。
- 失败后第一步是查询 `information_schema`，记录实际完成状态，不要立即重复执行原脚本。
- 对不可重复脚本，只生成“补缺失项”的修复 SQL，并由 DBA/负责人复核。
- 涉及唯一索引失败时保留原始重复数据证据，由业务决定合并或保留，不自动删除。
- 不建议应用回滚时删除新字段、索引或历史数据；当前迁移以向后兼容为目标。
- 数据库备份恢复演练必须在正式执行前完成，不能只确认“已有备份文件”。

## 14. 发布后验证清单

### 14.1 启动与结构

- [ ] Alarm、Push 使用 Java 8 和 UTF-8 启动。
- [ ] 两个服务从正确 profile 和 Data ID 加载配置。
- [ ] Alarm 连接 `hpis_alarm`，Push 连接 `hpis_push`。
- [ ] Alarm 分片基础表、所需字段和索引全部存在。
- [ ] Push 三张基础表和所需字段存在。
- [ ] 工单联合索引列顺序正确，`alarm_workorder`没有新增图片列。
- [ ] Nacos 中两个实例注册健康，8806/8812 正常监听。
- [ ] `alarm_queue`、`push.alarm` 及已启用配置动态队列的消费者数正常。

### 14.2 配置 API 与租户边界

- [ ] Alarm 配置通过接口完成新增、列表、详情、修改、删除。
- [ ] Push 配置通过接口完成新增、列表、详情、启用、禁用、删除。
- [ ] `deviceIds` 能解析为当前租户设备，跨租户设备被拒绝。
- [ ] 当前租户不能查询、修改或删除其他租户配置。
- [ ] 所有测试配置最终通过接口清理。
- [ ] 企业微信应用、用户绑定、接收组和候选负责人均以当前租户接口核验。
- [ ] 全部工单与我的工单边界正确，跨租户详情和写操作失败。

### 14.3 最终推送闭环

- [ ] HTTP `/alarm/alarmAdd` 路线能到达最终接收端。
- [ ] 真实 RabbitMQ `alarm_queue` 路线能到达最终接收端。
- [ ] 匹配当前租户报警配置且 `pushEnabled=1` 时发送 `push.alarm`。
- [ ] 未匹配配置或 `pushEnabled=0` 时不发送。
- [ ] Push 禁用后停止投递，重新启用后恢复。
- [ ] HTTP/MQTT/WebSocket 按实际启用通道分别验证，不以“只写入 MQ”代替最终接收。
- [ ] 工单未分配、转派、负责人完成和异常关闭均按状态机验证；完成图片从 `alarm_handle`回填。

可复用测试入口：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\studyProject\hpis2.0\hpis\hpis-alarm\src\test\resources\scripts\run-alarm-push-postman-e2e.ps1'

powershell -ExecutionPolicy Bypass `
  -File 'D:\studyProject\hpis2.0\hpis\hpis-alarm\src\test\resources\scripts\run-alarm-push-e2e.ps1'
```

第一个脚本要求 Nacos、MySQL、Redis、RabbitMQ 和已打包的 Alarm/Push JAR 就绪；它启动隔离服务和 HTTP 接收器，执行 78 请求的完整配置/工单 Collection，不执行 DDL。第二个脚本是较小的 RabbitMQ 入口回归，直接向真实 `alarm_queue`发布并验证最终 HTTP 接收。两者产生的报告和日志均位于 `target`，不得纳入提交。

当前测试库只读检查显示 `idx_alarm_workorder_tenant_assignee_status`不存在。测试脚本不会自动补索引；正式发布前必须按 12.6 节执行 `alarm-configure-workorder-migration.sql`并复核列顺序，不能因本地接口回归通过而跳过结构同步。

完整接口实例、请求响应和测试结果见 `doc/报警Push接口测试用例-全新环境.md`。

## 15. 回滚顺序

1. 先暂停报警流量或设置 `push.open=false`，阻止继续产生推送。
2. 回滚 Alarm 应用版本，再回滚 Push 应用版本。
3. 应用回滚时保留新增字段、表和索引，不执行破坏性逆向 DDL。
4. 仅需恢复旧的无配置兼容推送时，可临时设置
   `alarm.push.require-matched-config=false`，无需回滚数据库。
5. 故障恢复后先启动 Push、确认运行态路由恢复，再启动 Alarm 和恢复流量。
