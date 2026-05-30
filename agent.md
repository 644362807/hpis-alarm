# hpis-alarm Agent 协作规范

## 适用范围

本文件约束所有后续参与 `hpis-alarm` 模块修改的 Agent、Codex、Cursor、Claude Code 或人工协作流程。只要修改本模块的代码、配置、Mapper、测试或文档，都必须先阅读并遵守本文件。

## 代码修改注释要求

1. 每次新增或修改业务代码时，必须为本次改动涉及的关键逻辑补充中文注释。
2. 中文注释必须说明“为什么这样做”和“边界是什么”，不能只复述代码本身。
3. 涉及报警 start、stop、MQ ack/nack、Redis 去重、设备缓存、push、分片路由、事务、幂等、兜底、回滚、状态机的代码，必须在对应类、方法或关键分支旁写清楚风险边界。
4. 新增配置项时，必须在配置类字段、默认值兜底方法、运行配置文档中写清中文说明，至少包含默认值、非法值兜底、生产开启风险和回滚方式。
5. 新增批量 SQL、Mapper 方法或循环处理时，必须注释批量边界，例如 `IN` 上限、分片策略、失败降级和是否允许部分成功。
6. 新增 MQ listener、worker、线程池、定时任务或异步队列时，必须注释线程归属、ack 执行线程、shutdown 行为、异常是否重试、是否可能重复执行副作用。
7. 不给简单 getter/setter、普通字段赋值、无业务含义的临时变量写空泛注释；但如果这些字段参与幂等、状态流转或回滚，仍然必须写中文注释。

## 专门修改文档要求

每次完成一轮代码修改后，必须新增一份专门的中文修改文档，放在 `doc/` 目录下。文档文件名建议使用：

```text
本轮修改记录-YYYYMMDD-简短主题.md
```

专门修改文档必须只记录本轮新增或修改的内容，至少包含：

1. 修改背景。
2. 修改范围，精确到类、方法、配置项、Mapper 或文档。
3. 外部入口是否变化。
4. 数据库表结构是否变化。
5. MQ 消息体和 push payload 是否兼容。
6. 幂等风险。
7. 数据一致性风险。
8. 可观测性变化。
9. 配置开关和回滚方式。
10. 已执行验证。
11. 未覆盖风险和下一步建议。

## 资料文档同步要求

1. 新增专门修改文档后，必须在 `doc/报警第一阶段优化变更总览.md` 的中文主文档列表中追加索引。
2. 如果修改了运行配置，必须同步更新 `doc/报警运行配置说明.md`。
3. 如果修改了 MQ、insertAlarm、stop alarm、分片路由或 side effect 链路，必须同步更新 `doc/报警分片数据链路说明.md`。
4. 如果修改会影响压测口径、吞吐、批次大小或 worker 行为，必须同步更新 `doc/报警压测与Profiling测试报告.md`。
5. 文档更新必须是追加或补充事实，不允许删除历史压测结论；如果结论过期，需要明确写明“已被某日期验证替代”。

## 低风险重构约束

1. 默认保持 Controller、MQ queue、MQ 消息体、push payload 和公开 service 方法签名不变。
2. 默认通过 feature flag 灰度新链路，并保留旧链路回滚。
3. 不允许把“重构”和“业务语义改变”混在一起提交；如果必须改变语义，必须在专门修改文档中单独标注。
4. 对报警系统的 Redis、MQ、DB 状态机、worker 异步执行，必须优先保证失败后可重试、重复消费可控、生产排障有日志。
5. 提交前必须说明测试结果；如果无法执行测试，必须写明原因和剩余风险。

## SQL 批处理设计规则

1. 禁止在可能处理大批量数据的 `for`、`while`、`forEach` 循环中逐条执行 Mapper、Repository 或 Service 包装的 SQL。必须先收集业务键，再按固定上限分块执行批量查询、批量新增、批量更新或批量删除。
2. “批量 SQL”不等于“集合有多少条就一次提交多少条”。单条 SQL、单次 JDBC batch 和单个事务都必须设置硬上限；禁止把 2000、5000 或更多记录直接拼进一条超长 `IN`、`CASE WHEN`、多 values `INSERT` 或一次性大事务。
3. 默认从 `100-500` 条作为单批起始值，通过压测决定是否上调。上限必须由配置项或统一常量控制，并对非法值做兜底；不得直接使用待处理总量作为 batch size。
4. 涉及分表时，必须先按 `table_suffix` 分组，再在每个分片内按批量上限切 chunk。禁止一条 SQL 跨物理分片，也禁止因为分组后数量较小就跳过 chunk。
5. 批处理失败降级为逐条 SQL 只能作为小批量、短时间、可观测的兜底路径。必须限制最多降级条数，记录触发次数、批次大小、耗时和失败样本；高流量时不得长期依赖逐条兜底维持主链路。
6. 定时任务、MQ consumer、worker 在进入数据库前必须限制单轮最大处理量和单事务最大行数。积压量只用于判断调度策略，不能直接决定单次 SQL 或单事务大小。
7. 新增或修改批量 SQL 时，必须验证 SQL 长度、参数数量、执行耗时、事务耗时、锁等待、redo/undo 压力和失败回滚成本；压测至少覆盖正常批量、积压批量和失败降级三种路径。
8. 代码评审必须专项检查两类反模式：循环内 SQL 导致的 N+1 往返，以及无 chunk 的超大批量 SQL。发现后需要在修改文档中列出保留原因、风险、灰度方式和后续治理计划。

## 提交范围要求

1. 默认只提交 `src/` 和 `doc/` 下与本轮任务相关的文件。
2. 不提交 `target/`、`logs/`、本地压测运行产物、IDE 临时文件。
3. 如果工作区已有他人修改，不允许回滚；需要在最终说明中标明本轮实际修改范围。

## Maven 测试与打包规则

1. 本模块是父子工程的一部分，执行编译、测试或打包时必须从上层父工程目录 `D:\studyProject\hpis2.0\hpis` 发起。
2. 默认命令使用 `mvn -pl hpis-alarm -am ...`，让 Maven 从父工程解析并构建 `hpis-alarm` 所需的本地模块依赖。
3. 当前任务的测试边界只验收 `hpis-alarm` 模块；其它模块只作为依赖参与编译，不把其它模块的测试结果作为本轮通过条件。
4. 父工程依赖模块没有测试类时，允许使用 `-DfailIfNoTests=false` 避免空测试模块导致构建失败。
5. 如果 Windows 文件锁导致 `target/test-classes` 无法覆盖，必须先识别占用进程；不能为了测试随意停止 Nacos、RabbitMQ、MySQL 或正在运行的 `hpis-alarm` 服务。
6. 本地 `target/classes/mapper` 可能因为增量资源时间戳保留旧 XML。真实 jar 启动前必须校验源码 mapper 与生成资源哈希，或在确认 `hpis-alarm` 已停止后清理明确的陈旧生成资源再重新打包；禁止把 `target/` 生成文件提交进 Git。

## Java 8 与 Nacos UTF-8 启动规则

1. 本模块本地服务启动优先使用 Java 8：`C:\Program Files\Java\jdk1.8.0_321\bin\java.exe`。
2. 通过 `target\hpis-alarm.jar` 启动服务时，必须增加 JVM 参数 `-Dfile.encoding=UTF-8`，标准命令为：
   ```powershell
   & "C:\Program Files\Java\jdk1.8.0_321\bin\java.exe" -Dfile.encoding=UTF-8 -jar D:\studyProject\hpis2.0\hpis\hpis-alarm\target\hpis-alarm.jar
   ```
3. 该参数用于保证 Java 8 按 UTF-8 解析 Nacos 中的 `application-dev.yml`、`hpis-alarm-dev.yml` 等配置；缺少该参数时可能出现 `MalformedInputException`，进而导致数据源配置被忽略、`sqlSessionFactory/sqlSessionTemplate` 注入失败。
4. 如果 `target\hpis-alarm.jar` 被占用导致 Spring Boot repackage 失败，必须先确认占用进程是监听 `8806` 的 `hpis-alarm`，不能误杀 Nacos `8848/9848/9849`、RabbitMQ 或 MySQL。
