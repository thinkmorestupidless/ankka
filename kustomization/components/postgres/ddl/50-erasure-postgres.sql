-- The erasures a service has applied to its own tables (feature 042): one row per erasure, written
-- when the service has dropped the subject key from its caches, redacted its view rows, removed its
-- lookup tokens, ended the subject's agent sessions and run its erasure handler.
--
-- The service reads the highest erasure it applied when it opens its channel to the keyring, and the
-- keyring answers with every later one, which the service applies before it reports ready. A database
-- restored to before an erasure has lost the erasure's row with everything else, so the service
-- applies the erasure again: that is what keeps a restore from bringing an erased subject back.
CREATE TABLE IF NOT EXISTS ankka_erasures_applied (
  erasure_id       TEXT PRIMARY KEY,
  sequence         BIGINT NOT NULL,
  project          TEXT NOT NULL,
  subject          TEXT NOT NULL,
  applied_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  handler_outcome  TEXT,
  objects_erased   BIGINT,
  objects_final_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS ankka_erasures_applied_sequence ON ankka_erasures_applied (sequence);
