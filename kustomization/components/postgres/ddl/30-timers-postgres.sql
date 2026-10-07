-- Scheduled calls owned by the ankka timer runtime.
--
-- A table rather than in-memory timers because a scheduled call has to survive the node
-- that scheduled it. `due_at` is indexed because the sweeper's only query is
-- "what is due now".
CREATE TABLE IF NOT EXISTS ankka_timers (
  timer_name   TEXT PRIMARY KEY,
  component_id TEXT NOT NULL,
  method       TEXT NOT NULL,
  payload      BYTEA NOT NULL,
  due_at       TIMESTAMPTZ NOT NULL,
  attempts     INT NOT NULL DEFAULT 0,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ankka_timers_due_idx ON ankka_timers (due_at);

-- Recurring timers. Every statement below is safe to run again, on every start, while a runtime
-- from before them is still using the table: nullable columns with no default are a catalogue
-- change, and that runtime's statements name their columns.
--
-- A row whose `due_at` is 'infinity' is a recurring timer, and only such a row is. It is kept
-- there on purpose: a runtime from before recurring timers selects `due_at <= now`, so it never
-- runs a recurring timer and never deletes one, while its cancel, exists and replace by name
-- still mean what they say. Do not "tidy" `due_at` into a nullable column or move recurring
-- timers' next run into it: a finite `due_at` is fired once and deleted by that runtime.
--
--   period_millis  the period of a recurring timer; NULL for one that fires once
--   fire_at        when the sweeper next runs a recurring timer: its next due, or the end of a
--                  backoff; NULL for one that fires once, whose `due_at` says it
--   due_for        the due time the next run is for, which a handler is told: kept through a
--                  backoff, which moves only `due_at` or `fire_at`
ALTER TABLE ankka_timers ADD COLUMN IF NOT EXISTS period_millis BIGINT;
ALTER TABLE ankka_timers ADD COLUMN IF NOT EXISTS fire_at TIMESTAMPTZ;
ALTER TABLE ankka_timers ADD COLUMN IF NOT EXISTS due_for TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ankka_timers_fire_idx ON ankka_timers (fire_at) WHERE fire_at IS NOT NULL;
