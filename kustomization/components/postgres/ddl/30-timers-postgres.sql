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
