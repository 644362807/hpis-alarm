# Alarm MQ Load Test Report

- Run ID: `AUTO-20260512212928`
- Result: `PASS`
- Alarm count: `10`
- Expected stop count: `10`
- Month key: `202511`
- Alarm cid like: `{A26051221292828316-%`
- Send elapsed ms: `1247`
- Verify elapsed ms: `795`

## Terminal Snapshot

- Queue ready: `0`
- Alarm rows: `10`
- Closed rows: `10`
- Stop event status: `{APPLIED=10}`
- Side effect status: `{DONE=20}`
- Hot route status: `{CLOSED=10}`
- Stale route status: `{}`

## Max Observed

- Queue ready: `0`
- Alarm rows: `10`
- Closed rows: `10`
- Stop event status: `{APPLIED=10}`
- Side effect status: `{DONE=20}`
- Hot route status: `{CLOSED=10}`
- Stale route status: `{}`

## Criteria

- `alarmRows >= alarmCount`
- `closedRows >= expectedStopCount`
- `alarm_stop_event.APPLIED >= expectedStopCount`
- `alarm_stop_event.PENDING = 0`
- `alarm_stop_event.FAILED = 0`
- `alarm_queue.ready = 0`
