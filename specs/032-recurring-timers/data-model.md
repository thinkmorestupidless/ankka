# Data Model: Recurring Timers

What is stored, and what each statement does to it. Decisions are in [research.md](research.md);
the words here are the implementation's (row, sweeper, one-shot), not the glossary's.

## The table

`kustomization/components/postgres/ddl/30-timers-postgres.sql` keeps its `CREATE TABLE IF NOT
EXISTS` as it is and gains, after it, statements that are safe to run on every start:

```sql
ALTER TABLE ankka_timers ADD COLUMN IF NOT EXISTS period_millis BIGINT;
ALTER TABLE ankka_timers ADD COLUMN IF NOT EXISTS fire_at       TIMESTAMPTZ;
ALTER TABLE ankka_timers ADD COLUMN IF NOT EXISTS due_for       TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS ankka_timers_fire_idx ON ankka_timers (fire_at) WHERE fire_at IS NOT NULL;
```

| Column | One-shot timer | Recurring timer |
|---|---|---|
| `timer_name` (PK) | the name | the name |
| `component_id`, `method`, `payload` | the call | the call, made at every fire |
| `due_at` | when the sweeper next runs it: the due time, or the end of a backoff | `'infinity'`, always |
| `attempts` | failures since it was scheduled | failures since the last run that succeeded |
| `period_millis` | `NULL` | the period, `> 0` |
| `fire_at` | `NULL` | when the sweeper next runs it: `due_for`, or the end of a backoff |
| `due_for` | the due time it was scheduled with, kept through a backoff | the cadence point the next run is for |
| `created_at` | unchanged | unchanged |

**A row is recurring when `due_at = 'infinity'`**, and only then. `period_millis` alone does not
make it one: a runtime from before this feature that replaces a recurring timer writes a finite
`due_at` and leaves the other columns, and that row is the one-shot the old runtime meant (R1).

Every due time is a whole number of milliseconds (R4), so a period added to one is exact and the
value a handler is told equals the value stored. A period is from one millisecond to 36,500 days
(R6), which keeps every due time far inside what a `TIMESTAMPTZ` holds.

## Invariants

- One row per name. A one-shot and a recurring timer cannot share a name, in any mix of runtime
  versions, because both are the same row.
- The previous version's due query, `WHERE due_at <= $now`, never returns a recurring row.
- The previous version's `delete` and `exists`, by name, see a recurring row: an old instance
  can cancel one and is told it exists.
- For a recurring row, `fire_at >= due_for`. They differ while it waits out a backoff, or waits
  for an instance that has its handler.
- `due_for` of a recurring row only ever moves forward, by a whole number of periods.

## What each statement does

| Operation | Statement | Matched on |
|---|---|---|
| schedule a one-shot | upsert: `due_at = due`, `due_for = due`, `attempts = 0`, `period_millis = NULL`, `fire_at = NULL` | name |
| schedule a recurring timer | upsert: `due_at = 'infinity'`, `period_millis`; `fire_at`, `due_for` and `attempts` kept when the row is already recurring for the same component, handler and period, else `first`, `first`, `0` | name |
| cancel | `DELETE` | name |
| exists | `SELECT` | name |
| what is due | one-shots with `due_at <= now`, and recurring rows with `due_at = 'infinity' AND fire_at <= now`, oldest first, capped at the batch size | |
| a one-shot ran | `DELETE` | name and the `due_at` read |
| a one-shot failed | `attempts + 1`, `due_at = now + backoff`, `due_for = the due told` | name and the `due_at` read |
| a recurring timer ran | `due_for = next`, `fire_at = next`, `attempts = 0` | name, `due_at = 'infinity'` and the `due_for` read |
| a recurring timer failed | `attempts + 1`, `fire_at = now + backoff` | name, `due_at = 'infinity'` and the `due_for` read |
| a one-shot whose handler the sweeper does not have | `DELETE` | name and the `due_at` read |
| a recurring timer whose handler the sweeper does not have | `fire_at = now + 30 s`; `attempts` and `due_for` untouched | name, `due_at = 'infinity'` and the `due_for` read |

"Matched on" is what makes a statement a no-op when the row is not the one the sweeper read: the
handler replaced it, cancelled it, or another sweeper got there first during a singleton handoff.

## The next due

```text
next(dueFor, period, now) = dueFor + k × period, k the smallest whole number ≥ 1 with dueFor + k × period > now
```

One pure function, `Cadence.next`. When the handler succeeded within a period, `k = 1`. When the
service was down, the handler kept failing, or one run outlasted a period, `k > 1` and the periods
between are never fired.

## The due time a handler is told

| Row | Told |
|---|---|
| recurring | `due_for` |
| one-shot, `attempts = 0` | the `due_at` read |
| one-shot, `attempts > 0`, `due_for` set | `due_for` |
| one-shot, `attempts > 0`, `due_for` `NULL` (it failed under a runtime from before this feature) | the `due_at` read |

## Lifecycle

```text
one-shot:   scheduled ──due──▶ running ──done──▶ (deleted)
                                  │
                                  └─failed──▶ backing off ──▶ running …

recurring:  scheduled ──fire_at──▶ running ──done──▶ scheduled, at next(due_for)
                                      │
                                      └─failed──▶ backing off ──▶ running …   (due_for unchanged)

either:     cancel ──▶ (deleted)          schedule again ──▶ replaced, or — recurring and unchanged — kept
```

## What crosses the wire

| Value | Form |
|---|---|
| a period | `ScheduleRecurringRequest.period_millis`, milliseconds, 1 or more; a one-shot is scheduled by the call it always was and carries no period |
| a due time told to a process or a module | metadata `ankka.due`, milliseconds since the epoch in decimal, beside `ankka.timer` and `ankka.attempts` |

## Nothing else changes

No journal event, snapshot, durable state or view row is added or altered. The control plane and
the operator store nothing new; the operator's schema ConfigMap carries the changed file because
it carries every file.
