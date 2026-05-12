# Alarm MQ Load Test Report

- Run ID: `AUTO-20260512210759`
- Result: `FAIL`
- Alarm count: `10`
- Expected stop count: `10`
- Month key: `202511`
- Alarm cid like: `{AUTO-20260512210759-%`
- Send elapsed ms: `1281`
- Verify elapsed ms: `121233`

## Terminal Snapshot

- Queue ready: `0`
- Alarm rows: `0`
- Closed rows: `0`
- Stop event status: `{PENDING=10}`
- Side effect status: `{}`
- Hot route status: `{}`
- Stale route status: `{}`

## Max Observed

- Queue ready: `0`
- Alarm rows: `0`
- Closed rows: `0`
- Stop event status: `{PENDING=10}`
- Side effect status: `{}`
- Hot route status: `{}`
- Stale route status: `{}`

## Criteria

- `alarmRows >= alarmCount`
- `closedRows >= expectedStopCount`
- `alarm_stop_event.APPLIED >= expectedStopCount`
- `alarm_stop_event.PENDING = 0`
- `alarm_stop_event.FAILED = 0`
- `alarm_queue.ready = 0`
