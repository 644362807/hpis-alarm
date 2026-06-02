# Alarm MQ Gray Suite Manual Runbook

## Safety boundary

- Run from the parent reactor with Java 8 and `-Dfile.encoding=UTF-8`.
- Keep `RUN_ENABLED=false` in committed code.
- Before an IDE run, review the fields at the top of `AlarmMqGraySuiteRunnerTest`.
- The suite stops when `alarm_queue` is not empty. It never clears the queue.
- Apply `nacos-gray-candidate.yml` manually. The test tool never writes Nacos.
- Shared development gray runs require the remote-call stub.
- Before `ELECTROLYTIC` or `MIXED` runs, prepare the Redis sequence cache used by the configured
  `electrolyticIrmsSns` and `electrolyticSeqs`. The sender writes `redis-seed-commands.txt` beside each
  run report for review, but the gray suite never executes Redis commands automatically.

## IDE entry

1. Set `RUN_ENABLED=true`.
2. Select `SUITE`.
3. Set `CONFIRM_REMOTE_STUB=true`.
4. Review `MQ_*`, `MQ_MANAGEMENT_URL` and `JDBC_*` fields for the deployed environment.
5. For `SAFETY`, also set `CONFIRM_FAULT_INJECTION=true`.
6. For `SHARDING`, set Nacos `alarm.sharding.maxRowsPerSlice=10000` and set
   `CONFIRM_SHARDING_LIMIT=true`.
7. Run `AlarmMqGraySuiteRunnerTest.runManualGraySuite`.
8. Restore `RUN_ENABLED=false` after the run.

## Maven entry

```powershell
mvn -pl hpis-alarm -am `
  "-Dtest=AlarmMqGraySuiteRunnerTest#runManualGraySuite" `
  -DfailIfNoTests=false `
  "-Dfile.encoding=UTF-8" `
  test
```

## Recommended order

```text
SMOKE -> FUNCTIONAL -> TARGET_RATE -> SUSTAINED -> SAFETY -> SHARDING
```

Each execution writes reports below `target/alarm-graytest/<executionId>/`.
