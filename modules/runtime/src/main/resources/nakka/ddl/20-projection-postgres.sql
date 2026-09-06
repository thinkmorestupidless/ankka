-- Projection offset stores, from apache/pekko-projection r2dbc-int-test/ddl-scripts.
-- Journal/snapshot/durable_state tables are created by 10-journal-postgres.sql.

CREATE TABLE IF NOT EXISTS projection_offset_store (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  current_offset VARCHAR(255) NOT NULL,
  manifest VARCHAR(32) NOT NULL,
  mergeable BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY(projection_name, projection_key)
);

-- Timestamp based offsets are stored in this table.
CREATE TABLE IF NOT EXISTS projection_timestamp_offset_store (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  slice INT NOT NULL,
  persistence_id VARCHAR(255) NOT NULL,
  seq_nr BIGINT NOT NULL,
  -- timestamp_offset is the db_timestamp of the original event
  timestamp_offset timestamp with time zone NOT NULL,
  -- timestamp_consumed is when the offset was stored
  -- the consumer lag is timestamp_consumed - timestamp_offset
  timestamp_consumed timestamp with time zone NOT NULL,
  PRIMARY KEY(slice, projection_name, timestamp_offset, persistence_id, seq_nr)
);

CREATE TABLE IF NOT EXISTS projection_management (
  projection_name VARCHAR(255) NOT NULL,
  projection_key VARCHAR(255) NOT NULL,
  paused BOOLEAN NOT NULL,
  last_updated BIGINT NOT NULL,
  PRIMARY KEY(projection_name, projection_key)
);
