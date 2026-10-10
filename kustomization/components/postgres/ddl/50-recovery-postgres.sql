-- Backup and recovery (feature 041). Additive, like every file here: a database created before it
-- gains both tables at its next start, and nothing that was there changes.

-- The lines of history this database has been: one row per cluster a service first started on, with
-- the moment it did. A message published from a journal event carries the line its event belongs
-- to, the latest whose start is not after the event's own time, so an event published again after a
-- restore carries the id it carried, and one recorded after it never shares an id with one lost. A
-- failover writes no row: the line is the platform's, never Postgres's timeline.
CREATE TABLE IF NOT EXISTS ankka_history_lines (
  line_id TEXT PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The control plane's database only: written by the last step of its restore, read at its next
-- start, which holds projection to the cluster until a platform administrator releases it. Empty in
-- every service's database, where nothing reads it.
CREATE TABLE IF NOT EXISTS ankka_restore_marker (
  restored_at TIMESTAMPTZ NOT NULL,
  target_time TIMESTAMPTZ NOT NULL,
  released_at TIMESTAMPTZ,
  released_by TEXT
);
