# Alarm / Push 服务配置与启动手册

> 当前基线：2026-08-02；单实例；Nacos public Namespace + DEFAULT_GROUP；所有推送通道选配。

## 1. 使用边界

本文是Alarm和Push配置、启动与停机的当前执行依据。配置事实来自当前Java源码、`bootstrap.yml`和资源SQL。旧文档只用于追溯，不得覆盖本文的必填级别和推荐模式。

当前现场只有单实例，不需要自动分配workerId；Alarm固定`alarm.sharding.id.worker-id=0`。未来扩为多实例前，必须先实现或部署逐实例唯一workerId，不能让多个实例共享0。

## 2. 配置加载顺序

从低到高依次为：

1. JAR内`bootstrap.yml`：端口、服务名、默认`dev` profile、Nacos地址。
2. Nacos共享Data ID：`application-{profile}.yml`。
3. Nacos服务Data ID：`hpis-alarm-{profile}.yml`或`hpis-push-{profile}.yml`。
4. 环境变量、`SPRING_APPLICATION_JSON`。
5. `java -jar`后的`--key=value`启动参数。

当前不使用自定义Namespace和Group：Namespace保持public，Group保持`DEFAULT_GROUP`。生产启动必须显式覆盖`--spring.profiles.active=prod`，避免误读dev配置。

### 2.1 JAR启动前必须确认的引导项

| 配置 | 级别 | 当前值/推荐 | 中文含义 |
|---|---|---|---|
| `spring.application.name` | 固定 | Alarm=`hpis-alarm`，Push=`hpis-push` | 决定服务注册名和服务专属Data ID，现场不得随意改名 |
| `spring.profiles.active` | 生产启动必填 | `prod` | 内置默认仍是`dev`；不显式覆盖会读取开发配置 |
| `spring.cloud.nacos.discovery.server-addr` | 启动必填 | 按现场 | Nacos服务注册地址 |
| `spring.cloud.nacos.config.server-addr` | 启动必填 | 按现场 | Nacos配置中心地址，通常与注册地址相同 |
| `spring.cloud.nacos.discovery.username/password` | 启动必填 | 按现场 | 注册中心认证；不要依赖JAR内本地`nacos/nacos` |
| `spring.cloud.nacos.config.username/password` | 启动必填 | 按现场 | 配置中心认证 |
| `spring.cloud.nacos.config.file-extension` | 固定 | `yml` | 当前Data ID后缀，改成yaml/properties会导致读取不到现有配置 |
| `spring.cloud.nacos.config.context-path` | 生产显式 | `/nacos` | Nacos HTTP上下文路径 |
| Namespace / Group | 固定 | public / `DEFAULT_GROUP` | 当前不配置自定义namespace和group |

仓库`bootstrap.yml`是本地开发引导值，仍写着`dev`、`127.0.0.1:8848`和本地Nacos账号。正式部署必须通过外部引导配置、环境变量或启动参数覆盖；不需要修改Namespace和Group。

## 3. 配置级别

| 级别 | 含义 |
|---|---|
| 启动必填 | 缺少后服务启动失败，或核心数据源不能工作 |
| 条件必填 | 只有启用对应能力时必须提供 |
| 生产显式 | 代码有默认值，但生产必须写清以冻结行为 |
| 可选 | 不配置使用代码安全默认值 |
| 排障临时 | 只允许短时覆盖，排障后恢复 |
| 生产禁止 | 只允许本地或隔离压测 |

## 4. 公共依赖配置

| 配置 | 级别 | 默认/推荐 | 中文含义与注意点 |
|---|---|---|---|
| `spring.redis.host/port/database/password` | 启动必填 | 按现场 | 登录、设备、Push路由和`sys_dict2:*`字典缓存 |
| `spring.redis.timeout` | 生产显式 | `5000ms` | Redis命令超时 |
| `spring.redis.lettuce.pool.min-idle` | 生产显式 | 低配1/标准2 | 最小空闲连接 |
| `spring.redis.lettuce.pool.max-idle` | 生产显式 | 低配4/标准8 | 最大空闲连接 |
| `spring.redis.lettuce.pool.max-active` | 生产显式 | 低配8/标准16 | 单实例最大Redis连接 |
| `spring.redis.lettuce.pool.max-wait` | 生产显式 | `3000ms` | 池耗尽后的等待时间 |
| `spring.rabbitmq.host/port/virtual-host/username/password` | 启动必填 | 按现场 | Alarm消费`alarm_queue`、发送`push.alarm`，Push消费并创建动态队列 |
| `publisher-confirm-type` | 生产显式 | `correlated` | 发布确认 |
| `publisher-returns` | 生产显式 | `true` | 不可路由消息退回 |
| `listener.simple.default-requeue-rejected` | 生产显式 | `true` | listener异常后的默认重入队语义，修改会影响重复消费风险 |
| `listener.simple.missing-queues-fatal` | 生产显式 | `false` | 队列稍后声明时不阻断启动 |

连接池总原则：同一MySQL实例上所有服务最大连接数之和建议不超过`max_connections`的60%～70%，为管理连接、DDL和故障恢复保留空间。线程池最大线程数不能脱离数据库连接上限单独放大。

## 5. Alarm启动必填配置

| 配置 | 默认 | 中文含义 |
|---|---|---|
| `file.path` | 无 | 临时图片/文件目录。Windows示例`D:/hpis-data/alarm/`，Linux示例`/opt/hpis/data/alarm/`；必须人工创建并验证部署账号可写 |
| `thread.pool.core-pool-size` | 无 | Alarm公共异步线程池核心线程数 |
| `thread.pool.maximum-pool-size` | 无 | 最大线程数，必须大于等于核心线程数 |
| `thread.pool.work-queue-size` | 无 | 等待队列容量 |
| `spring.shardingsphere.datasource.ds.url/username/password` | 无 | 新时间容量分片物理数据源；`alarm.sharding.enabled=true`时必填 |

`thread.pool.keep-alive-time`虽然能绑定，但当前`ThreadPoolFactory`没有将它设置到线程池，只作为历史兼容位，不是实际启动必填。

Alarm开启新分片时，`AlarmDynamicShardingConfig`会直接`new HikariDataSource()`，当前只把`url/username/password/driver-class-name`写入该数据源。因此旧Nacos中`spring.shardingsphere.datasource.ds.initial-size/max-active/max-wait/...`等Druid池参数在新分片模式下不生效；Alarm当前使用Hikari库默认池大小（通常最大10）。现场需要扩大Alarm DB连接池时，必须先做代码配置能力改造和压测，不能只在Nacos填`max-active`并认为已生效。

## 6. Alarm核心配置字典

### 6.1 推送、分片与ID

| 配置 | 默认 | 级别 | 中文含义/推荐 |
|---|---:|---|---|
| `push.open` | `false` | 生产显式 | Alarm推送总开关；正常生产推荐`true`，维护/止推为`false` |
| `alarm.push.require-matched-config` | `true` | 生产显式 | 只有匹配当前租户Alarm配置才发送Push |
| `alarm.sharding.enabled` | `false` | 生产显式 | 当前升级完成后推荐`true`；不得与旧inline `%5`规则同时启用 |
| `max-rows-per-slice` | `5000000` | 生产显式 | 单月单物理表容量，合法范围1..8388608 |
| `allocation-segment-size` | `1000` | 生产显式 | 每次从DB预占的行号段；崩溃可能产生无害空洞 |
| `pre-create-months` | `0` | 可选 | 启动时预创建未来月份数；当前由月末任务负责下一月 |
| `include-legacy-tables` | `false` | 废弃 | 当前路由不读取旧0..4表，保持false |
| `id.worker-id` | `0` | 生产显式 | 单实例固定0；多实例必须逐实例唯一0..255 |
| `actual-data-nodes.current-month-max-slice-no` | `255` | 生产显式 | 当前月预注册的最大两位/三位逻辑slice范围上限 |
| `actual-data-nodes.next-month-max-slice-no` | `9` | 生产显式 | 下一月预注册00..09 |

### 6.2 CID路由生命周期

| 配置 | 默认 | 中文含义 |
|---|---:|---|
| `cid-index.hot-hours` | 24 | 未关闭报警在热点CID表保留小时数 |
| `cid-index.stale-expire-days` | 30 | stale路由保留天数 |
| `cid-index.cleanup-batch-size` | 1000 | 已关闭路由清理批量 |
| `cid-index.transfer-batch-size` | 1000 | hot转stale批量 |
| `cid-index.cleanup-cron` | `0 0 2 * * ?` | 路由清理cron |
| `cid-index.transfer-cron` | `0 10 2 * * ?` | hot转stale cron |
| `cid-index.stale-timeout-cron` | `0 20 2 * * ?` | stale超时处理cron |
| `cid-index.log-enabled` | false | 普通完成日志，排障时短开 |

### 6.3 分片规则刷新

| 配置 | 默认 | 中文含义 |
|---|---:|---|
| `rule-refresh.enabled` | true | 启用分片DataSource规则重建与代理切换 |
| `rule-refresh.cron` | `0 5 0 1 * ?` | 每月1日00:05刷新 |
| `rule-refresh.close-old-delay-ms` | 300000 | 旧DataSource延迟关闭，保护在途事务 |
| `active-on-table-created-enabled` | true | 动态建表后触发规则刷新 |
| `active-on-table-created-debounce-ms` | 30000 | 建表刷新防抖时间 |
| `active-on-table-created-async` | true | 在调用线程外刷新 |
| `month-end-pre-create-enabled` | true | 月末预建下月表 |
| `month-end-pre-create-cron` | `0 50 23 * * ?` | 每日23:50运行，由代码判断最后一天 |
| `month-end-pre-create-check-last-day` | true | 只在最后一天执行 |
| `month-end-pre-create-next-month-max-slice-no` | 0 | 默认只建下月00表 |

### 6.4 Alarm批量链路

| 配置 | 默认 | 中文含义/约束 |
|---|---:|---|
| `batch.stop-enabled` | true | stop按分片批量处理；false回退单条route查询 |
| `batch.insert-enabled` | true | 内部批量入库API |
| `batch.in-limit` | 500 | SQL IN和批量上限 |
| `fallback-single-on-batch-error` | true | 小批量失败时拆单兜底 |
| `fallback-single-max-items` | 100 | 拆单最大条数，硬上限500 |
| `insert-consumer-batch-enabled` | false | true时批量listener接管`alarm_queue`，与单条listener互斥 |
| `insert-consumer-batch-size` | 100 | consumer batch大小 |
| `insert-consumer-batch-receive-timeout-ms` | 50 | 凑批最长等待 |
| `insert-consumer-batch-concurrency` | `2-4` | 批量consumer并发，硬上限32 |
| `insert-consumer-batch-prefetch` | 100 | 至少等于batch size，硬上限2000 |
| `insert-consumer-batch-log-enabled` | false | 批量成功日志 |
| `insert-item-profile-log-enabled` | false | 单条阶段profiling，生产默认关闭 |
| `stop-event-batch-enabled` | true | stop事件批量upsert |
| `stop-event-upsert-batch-size` | 100 | stop事件upsert批量，硬上限500 |
| `electrolytic-snapshot-batch-size` | 100 | 电解槽快照批量 |
| `electrolytic-snapshot-mode` | `SYNC` | `SYNC/DUAL_WRITE/ASYNC`，必须按顺序灰度 |
| `start-lock-retry-max-attempts` | 3 | start事务瞬时锁冲突重试，硬上限3 |

### 6.5 Stop worker

生产推荐保持源码默认。关键项如下：

| 配置 | 默认 | 中文含义 |
|---|---:|---|
| `dispatch-enabled` | true | 是否认领新stop事件；false不影响MQ stop先落库 |
| `dispatch-interval-ms` | 100 | 派发扫描间隔 |
| `worker-threads` | 4 | 独立worker线程，安全范围1..32 |
| `claim-batch-size` | 200 | 每次认领数，硬上限500 |
| `max-in-flight-batches` | 4 | 单实例最大在途批次 |
| `processing-timeout-ms` | 60000 | PROCESSING超时回收阈值 |
| `claim-recovery-batch-size` | 500 | 超时回收批量 |
| `claim-recovery-interval-ms` | 10000 | 超时回收间隔 |
| `processing-retry-delay-ms` | 1000 | 核心事务失败后的重试延迟 |
| `claim-retry-max-attempts` | 3 | claim死锁重试，安全范围1..5 |
| `claim-retry-backoff-ms` | 25 | claim退避时间 |
| `route-missing-retry-delay-ms` | 5000 | route暂不可见重试延迟 |
| `initial-available-delay-ms` | 0 | 首次认领聚批窗口，范围0..5000 |
| `low-watermark/high-watermark` | 500/5000 | 流量档位水位 |
| `normal-batch-size/high-batch-size` | 500/2000 | 正常/高流量处理量 |
| `normal-interval-ms/high-interval-ms` | 1000/100 | 正常/高流量扫描间隔 |
| `max-parallelism` | 4 | 高流量单轮最大并行度 |
| `side-effect-enabled` | true | 结束后的远程同步、清理和Push副作用 |
| `applied-retention-minutes` | 30 | APPLIED事件保留时间 |
| `cleanup-batch-size/interval-ms` | 1000/60000 | 清理批量和间隔 |
| `failed-retention-days` | 7 | FAILED保留天数配置 |
| `cleanup-only-low-traffic` | true | 只在低流量清理 |
| `max-retry` | 5 | 异常达到次数后转FAILED |
| `route-missing-recovery-*` | 500/1000/10000 | ROUTE_MISSING恢复批量、延迟和间隔 |
| `idle-pause-enabled` | true | 空闲后减少DB轮询 |
| `idle-confirm-count` | 3 | 进入idle pause的连续空轮数 |
| `idle-probe-interval-ms` | 60000 | idle后的兜底探针间隔 |
| `log-enabled` | false | 普通worker完成日志 |

### 6.6 电解槽快照worker

`SYNC`模式下`dispatch-enabled=false`。进入`ASYNC`前必须先开启worker并在`DUAL_WRITE`下验证积压归零和投影一致。

默认值：派发间隔100ms、线程2、认领100、在途2、超时60000ms、claim重试3/50ms、业务重试5000ms、首次延迟2000ms、最大重试5、回收500/10000ms、普通日志关闭、idle确认3、探针1000ms。

### 6.7 日志、去重和测试桩

| 配置 | 默认 | 级别 | 中文含义 |
|---|---:|---|---|
| `alarm.sql-log.enabled` | false | 排障临时 | SQL信息日志总开关 |
| `mode` | `alarm-write` | 排障临时 | `all/alarm-write/slow` |
| `print-param` | false | 排障临时 | 打印参数和完整SQL，可能包含敏感业务值 |
| `slow-enabled/slow-ms` | true/200 | 排障临时 | 慢SQL开关和阈值 |
| `alarm.dedup.disconnect.ttl-seconds` | 1800 | 可选 | 断线报警Redis去重TTL |
| `alarm.internal-test.remote-call-stub-enabled` | false | 生产禁止true | 截断Feign/WebSocket/文件等远程调用 |
| `alarm.internal-test.push-mq-stub-enabled` | false | 生产禁止true | 截断`push.alarm`MQ投递 |

## 7. Push配置字典

### 7.1 启动必填

Push动态数据源的`url/username/password`和线程池`core-pool-size/maximum-pool-size/work-queue-size`必填。`keep-alive-time`当前未用于线程池创建。

### 7.2 动态队列和站内路由

| 配置 | 默认 | 中文含义 |
|---|---:|---|
| `queue.push.maxLength` | 1000 | 动态队列最大消息数 |
| `queue.push.maxLengthBytes` | 10485760 | 动态队列最大累计字节，10MiB |
| `queue.push.messageTTL` | 60000 | 单消息TTL，毫秒 |
| `queue.push.expiresTime` | 3600000 | 队列无使用后过期时间，毫秒 |
| `push.inapp.route-mode` | `TENANT_ALL` | `TENANT_ALL`按租户全量；`MESSAGE_TYPE`恢复租户+类型路由 |
| `push.routing.dict-exclude-filter-enabled` | false | 主动配置等级排除开关；首次发布false，准备完成后true |

等级过滤只影响主动配置队列选择；`TENANT_ALL`站内会话路由不按等级过滤。消息没有`messageLevel`时按类型正常推送。目录元数据首次加载失败、或Redis中配置快照暂时缺失时，当前实现采用“放行避免漏报”；这会导致过滤短时不生效，所以冷启动必须核对目录加载顺序和配置缓存。

### 7.3 企业微信（全部条件选配）

| 配置 | 默认 | 中文含义 |
|---|---:|---|
| `push.wecom.secret-key` | 空 | 启用企业微信时必填；Base64解码后32字节AES-GCM密钥 |
| `base-url` | 企业微信官方地址 | API根地址，本地联调可覆盖假服务 |
| `connect-timeout-ms/read-timeout-ms` | 5000/5000 | 连接和读取超时 |
| `max-recipients-per-request` | 1000 | 单请求最大接收人数 |
| `max-attempts` | 3 | 网络异常最大尝试次数 |
| `max-text-bytes` | 2048 | 文本消息最大字节数 |

不使用企业微信时密钥可以为空；只有调用企业微信应用配置或实际投递时才需要密钥。密钥一旦用于加密数据库中的corpSecret，后续重启必须保持不变，否则旧密文无法解密。

HTTP、MQTT、邮件、WebSocket和企业微信都不是Push服务启动必选通道，是否启用由数据库中的主动推送配置决定。

## 8. 推荐运行模式

### 8.1 标准单实例生产（当前推荐）

- 新时间容量分片开启，workerId=0。
- Alarm单条MQ listener，批量内部API保留。
- Stop worker开启。
- 电解槽快照`SYNC`，异步worker关闭。
- Alarm内部测试桩关闭。
- Push站内`TENANT_ALL`。
- 等级过滤第一次启动false，验收后true。

### 8.2 维护迁移

首选停止Alarm和Push。只能启动HTTP检查时：`push.open=false`，Push过滤false，并用启动覆盖关闭simple/direct Rabbit listener。DDL期间不得让分片建表、MQ消费或worker并发运行。

### 8.3 高吞吐灰度

只有压测通过后才将`insert-consumer-batch-enabled=true`。开启后批量listener接管`alarm_queue`；必须核对消费者数、prefetch、数据库连接池、死锁和nack/requeue。

### 8.4 本地隔离测试

允许开启两个`alarm.internal-test.*`桩和假企业微信地址，但必须使用独立租户、设备前缀和端口，结束后清理。生产模板始终为false。

## 9. 资源档位建议

| 档位 | Alarm线程池core/max/queue | Push线程池core/max/queue | Alarm DB池 | Push Druid max-active | Redis max-active | 说明 |
|---|---|---|---|---:|---:|---|
| 低配 | 4/8/2000 | 2/6/500 | 当前Hikari默认，不支持用Nacos调池大小 | 10 | 8 | 小数据量、CPU/内存受限现场，保持单条MQ listener |
| 标准 | 10/20/5000 | 6/12/1000 | 当前Hikari默认（通常max=10） | 20 | 16 | 当前单实例推荐起点 |
| 较高负载 | 压测确定 | 压测确定 | 先改造为可配再压测 | 按DB上限计算 | 32以内起测 | 必须验证P95、积压、连接等待和死锁 |

已有现场若稳定使用更大的20/37/15000，不要求仅因本文立即缩容；应结合CPU、队列等待、DB连接利用率做灰度调整。

## 10. 发布和启动顺序

1. 备份数据库，执行结构预检。
2. 完成Alarm/Push DDL。
3. 通过System接口维护`push_message_group`、`alarm_rank=1/2/3`，刷新并验证`sys_dict2:*`。
4. 为现有messageType补齐目录10/25。
5. 发布并启动Push，过滤保持false。
6. 验证Push冷启动读取目录、主动配置和动态队列。
7. 发布并启动Alarm，`push.open=true`。
8. 验证旧行为后开启Push等级过滤并重启Push。
9. 执行等级、无等级、非过滤类型和站内推送矩阵。

PowerShell示例：

```powershell
& 'C:/Program Files/Java/jdk1.8.0_321/bin/java.exe' `
  -Dfile.encoding=UTF-8 `
  -jar 'D:/hpis/hpis-push.jar' `
  --spring.profiles.active=prod `
  --spring.cloud.nacos.discovery.server-addr=<NACOS_HOST:PORT> `
  --spring.cloud.nacos.config.server-addr=<NACOS_HOST:PORT>

& 'C:/Program Files/Java/jdk1.8.0_321/bin/java.exe' `
  -Dfile.encoding=UTF-8 `
  -jar 'D:/hpis/hpis-alarm.jar' `
  --spring.profiles.active=prod `
  --spring.cloud.nacos.discovery.server-addr=<NACOS_HOST:PORT> `
  --spring.cloud.nacos.config.server-addr=<NACOS_HOST:PORT> `
  --alarm.sharding.id.worker-id=0
```

Nacos账号密码使用外部`bootstrap.yml`或部署平台密钥注入，不要把真实密码写进启动脚本和文档。服务顺序为Push先、Alarm后；依赖顺序为MySQL/Redis/RabbitMQ → Nacos → Push → Alarm。

## 10.1 旧Nacos残留项处理

当前开发Data ID中还能看到`alarm.profile.*`、`spring.shardingsphere.datasource.ds.initial-size/max-active/...`以及驼峰拼写的旧worker/分片配置。当前源码没有`alarm.profile`配置类；新分片Alarm数据源也不读取上述Druid池参数。新建`prod` Data ID时不要整份复制旧`dev`，应以交付包`config/`模板为起点；历史项删除前先对照本文“配置事实来源”确认。

## 11. 启动验收

- Nacos：两个服务均注册到public/DEFAULT_GROUP。
- 日志：出现`Started HpisPushApplication`、`Started HpisAlarmApplication`。
- Alarm：出现三个逻辑表actualDataNodes刷新日志，无`Actual table ... is not in table config`。
- MySQL：Alarm连接`hpis_alarm`，Push连接`hpis_push`。
- RabbitMQ：`alarm_queue`、`push.alarm`和启用配置队列消费者正常。
- Redis：登录、设备路由及`sys_dict2:push_message_group`、`sys_dict2:alarm_rank`存在。
- HTTP：`/alarm/list`使用`startTime/endTime`返回HTTP 200和业务code 200。
- Push：目录选项树非空，配置10/25恢复，动态队列存在。

Actuator health不是唯一判断依据；邮件未配置或旧JDBC驱动不支持`isValid`时可能影响聚合health，但业务数据库、MQ和接口仍需分别验证。

## 12. 快速止损和回退

- 停止所有Alarm推送：`push.open=false`并重启Alarm。
- 关闭等级过滤：`push.routing.dict-exclude-filter-enabled=false`并重启Push。
- 恢复旧站内类型路由：`push.inapp.route-mode=MESSAGE_TYPE`并重启Push。
- 暂停stop认领：`alarm.stop-worker.dispatch-enabled=false`；MQ stop仍先落事件表。
- ASYNC快照异常：先回`DUAL_WRITE`或`SYNC`，等待命令表处理稳定后关闭worker。

## 13. 配置事实来源

- Alarm：`AlarmShardProperties`、`AlarmBatchProperties`、`AlarmStopWorkerProperties`、`AlarmElectrolyticSnapshotWorkerProperties`、`AlarmSqlLogProperties`、`AlarmInternalTestProperties`。
- Push：`SessionQueueManager`、`WebSocketMessagePushService`、`WecomSecretCrypto`、`WecomApiClient`、`WecomDeliveryService`、`WecomMessageBuilder`。
- 配置模板位于交付包`config/`。代码新增配置时必须先更新事实清单和模板，再更新本手册。
