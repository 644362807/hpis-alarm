# Alarm MQ Load Test Report

- Run ID: `AUTO-20260512213104`
- Result: `PASS`
- Alarm count: `20000`
- Expected stop count: `20000`
- Month key: `202511`
- Alarm cid like: `{A260512213104b4f-%`
- Send elapsed ms: `2104`
- Verify elapsed ms: `393224`

## Terminal Snapshot

- Queue ready: `0`
- Alarm rows: `20000`
- Closed rows: `20000`
- Stop event status: `{APPLIED=20000}`
- Side effect status: `{DONE=7000, PENDING=33000}`
- Hot route status: `{CLOSED=20000}`
- Stale route status: `{}`

## Max Observed

- Queue ready: `37933`
- Alarm rows: `20000`
- Closed rows: `20000`
- Stop event status: `{PENDING=12264, APPLIED=20000}`
- Side effect status: `{DONE=7000, PENDING=33397}`
- Hot route status: `{ACTIVE=20000, CLOSED=20000}`
- Stale route status: `{}`

## Criteria

- `alarmRows >= alarmCount`
- `closedRows >= expectedStopCount`
- `alarm_stop_event.APPLIED >= expectedStopCount`
- `alarm_stop_event.PENDING = 0`
- `alarm_stop_event.FAILED = 0`
- `alarm_queue.ready = 0`
