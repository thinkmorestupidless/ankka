# Contract: the sidecar protocol at 1.12

What a process and a module see. Decisions are in [research.md](../research.md) (R4, R7).

## `client.proto`

```protobuf
service Client {
  // … the calls of 1.11, unchanged, Schedule and Cancel among them …
  rpc ScheduleRecurring (ScheduleRecurringRequest) returns (ScheduleRecurringReply);
}

message ScheduleRecurringRequest {
  string  timer_id      = 1;
  int64   delay_millis  = 2;   // from now to the first due; 0 or less is due at once
  int64   period_millis = 3;   // from each due to the next; from 1 to 36,500 days' worth
  string  component_id  = 4;   // a timed action
  string  name          = 5;   // one of its handlers
  Payload payload       = 6;
}

message ScheduleRecurringReply { Error error = 1; }   // unset: scheduled
```

`ScheduleRequest` and `CancelRequest` are unchanged. `Cancel` cancels either kind.

The request has no `kind` and no `entity_id`: a timer's target is a timed action's handler.

A refusal or a fault is an `Error` in the reply, never a gRPC status, so that a module's import
can carry it:

| Case | `ErrorCode` |
|---|---|
| a period of zero or less, or of more than 36,500 days; an empty timer id; a payload over 1,024 bytes; a component id or a name that does not parse | `BAD_REQUEST` |
| timers are not running in this service | `UNAVAILABLE` |
| the database cannot be reached | `UNAVAILABLE` |

The message for a refused period is the one `TimerRules.period` gives, naming the timer.

Scheduling again under a `timer_id` that already holds a recurring timer for the same
`component_id`, `name` and `period_millis` keeps its next due and takes the payload;
`delay_millis` is not used. Anything else under an existing `timer_id` replaces it.

## `timed_action.proto`

Unchanged. `TimedActionRequest.metadata` carries one more entry on every request:

| Key | Value |
|---|---|
| `ankka.timer` | the timer's id (as today) |
| `ankka.attempts` | how many times it has failed, from `0` (as today) |
| `ankka.due` | the due time this run is for: milliseconds since the epoch, in decimal |

`ankka.due` is the same on every retry of one due time, and for a recurring timer successive
values differ by a whole number of periods. It is the form `ankka.now` already has.

## The `ankka1` imports

| Import | Request | Reply |
|---|---|---|
| `schedule_recurring` | `ScheduleRecurringRequest` | `ScheduleRecurringReply` |

`(ptr, len) -> i64`, as `schedule` is. It blocks the calling instance. Before the service is
bound it answers `Error(UNAVAILABLE)`. Unlike `schedule`, a refusal is a reply and not a trap.

`ankka1_timed_action` is unchanged; its request carries `ankka.due` beside `ankka.now`.

## Version

- The protocol version is `1.12`, written in `WireProtocol.Version`,
  `controlplane-api`'s `Protocol.version`, `protocol/README.md` and each SDK's constant.
- A 1.11 process or module runs unchanged on a 1.12 runtime. It is sent `ankka.due` and ignores it.
- A process built for 1.12 calling `ScheduleRecurring` on a 1.11 runtime gets gRPC `UNIMPLEMENTED`;
  each SDK reports it as the runtime's protocol being too old for recurring timers, naming both
  versions. Nothing is scheduled.
- A module that calls `schedule_recurring` imports it and does not instantiate on a 1.11 runtime;
  a module that does not call it does not import it.
- A 1.12 process on a 1.11 runtime is not sent `ankka.due`; each SDK's accessor answers "none".

## The sidecar

`ClientLogic` gains `scheduleRecurring` over `TimerScheduler.createRecurringTimer`. `ClientService`
and `HostImports` call it and nothing else, so a process and a module get one behaviour. An
`IllegalArgumentException` from the scheduler becomes `Error(BAD_REQUEST)` with its message.

The sweeper sets `ankka.due` where it sets `ankka.timer` and `ankka.attempts`; `Translate` passes
metadata through unchanged, as it does today.

## Conformance cases

Each SDK's conformance service adds to its `reminder` timed action a handler `tick(id)` that
records `due:<ankka.due>` on the `conformance` entity `id`, and three routes:

| Route | Does |
|---|---|
| `POST /conformance/recur/{id}` | schedules `recur-{id}`: delay 0, period 1,000 ms, `reminder`/`tick`, payload `id`. Answers 204 |
| `POST /conformance/recur/{id}/again` | the same, with a delay of 60,000 ms, which a replacement would wait out. Answers 204 |
| `POST /conformance/recur/{id}/cancel` | cancels `recur-{id}`. Answers 204 |
| `POST /conformance/recur-refused/{id}` | schedules `recur-{id}` with period 0. Answers 400 with the refusal's message |

| Case | Asserts | Scenario of `features/timers/languages.feature` |
|---|---|---|
| `timer.recurring.cadence` | three `due:` records, each 1,000 after the one before | a recurring timer fires once for each period in every language |
| `timer.recurring.due-time` | the `due:` records are on the grid of the timer table's next due for `recur-{id}`, which is after them all: what the handler was told is the runtime's own cadence | a handler is told the due time of the timer that ran it in every language |
| `timer.recurring.set-again` | after `/again`, two more `due:` records arrive within seconds on the same grid; a replacement would not run for a minute | a recurring timer set again keeps its next due time in every language |
| `timer.recurring.cancel` | after the cancel the row is gone and no further `due:` record arrives within three periods | a cancelled recurring timer does not fire again in every language |
| `timer.recurring.refused` | 400, the message names `recur-{id}`, and there is no row | (the refusal, below the features) |

Run with a filter, the glob needs its leading wildcard: `'*timer.recurring.*'`.
