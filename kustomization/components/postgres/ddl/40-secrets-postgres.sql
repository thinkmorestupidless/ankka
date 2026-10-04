-- A service's secret store: values the service keeps and reads back by name, each encrypted
-- with the service's secret key (ANKKA_SECRET_KEY) before it reaches this table.
--
-- The table belongs to the secret store alone. It is not an entity's journal or state, and no
-- projection reads it, so nothing kept here is ever replayed into a view or a snapshot. Two
-- services never share a database, so the name alone is the key.
CREATE TABLE IF NOT EXISTS ankka_secrets (
  name       TEXT PRIMARY KEY,
  ciphertext BYTEA NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
