package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.runtime.SqlSyntax.sql

/**
 * The SQL behind a view's row table.
 *
 * One table per view: `row_key` is the source entity's id, `payload` is the row encoded by the
 * view's own serializer.
 *
 * The payload is `TEXT` rather than `JSONB` because r2dbc's Postgres driver maps `jsonb` to a
 * driver-specific `Json` type, and storing text keeps the column readable by any client. Queries
 * cast on the way in (`payload::jsonb->>'email'`), which Postgres can index with an expression
 * index.
 */
private[ankka] object ViewStore:

  /**
   * Taken before creating tables, transaction-scoped, released on commit or rollback.
   *
   * `CREATE TABLE IF NOT EXISTS` is not safe under concurrency in Postgres: two sessions can both
   * pass the existence check and the loser fails on `pg_type`'s unique index. That was academic
   * while a service had one node; several now cold-start at once and each creates every view table
   * (feature 004, research R11). One constant key for all of ankka's DDL is right — the contention
   * is between nodes of one service, on one database, and it lasts milliseconds.
   */
  val schemaLock: SqlFragment = SqlFragment.raw(s"SELECT pg_advisory_xact_lock($SchemaLockKey)")

  /** Arbitrary, stable, and the same one the schema-init container takes. */
  val SchemaLockKey: Long = 6_2716_5225_00L

  def createTable(table: String): SqlFragment =
    SqlFragment.raw(
      s"""CREATE TABLE IF NOT EXISTS $table (
         |  row_key    TEXT PRIMARY KEY,
         |  payload    TEXT NOT NULL,
         |  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
         |)""".stripMargin
    )

  /**
   * The channel every write of a view's row is announced on, as `<table>|<row key>`, and a rebuild
   * as `<table>|!rebuilt`. Postgres delivers a notification when the transaction that raised it
   * commits and never when it rolls back, so a watcher on any instance hears of a write exactly
   * when it can read it.
   */
  val Channel: String = "ankka_views"

  /** What a rebuild is announced as, after the table's name. */
  val Rebuilt: String = "!rebuilt"

  /**
   * Postgres refuses a notification of 8000 bytes or more, and a refused notification would fail
   * the write: a row whose key is longer is written and not announced.
   */
  val AnnouncedKeyBytes: Int = 7900

  /**
   * Announces the keys `written` returns, in the same statement as the write: whatever runs a write
   * — a projection's session, a transaction, a guard — runs its announcement with it, and no path
   * can take one without the other. One row of one column, so the statement still reports a count
   * to a session that reads one.
   */
  private def announcing(table: String, written: SqlFragment): SqlFragment =
    SqlFragment.raw("WITH written AS (") ++ written ++ SqlFragment.raw(
      s") SELECT count(pg_notify('$Channel', '$table|' || row_key)) FROM written " +
        s"WHERE octet_length(row_key) < $AnnouncedKeyBytes"
    )

  /**
   * Insert-or-replace, announced. Uses `EXCLUDED.payload` rather than binding the payload twice,
   * because parameters are numbered by position and a repeated placeholder would need the value
   * bound again.
   */
  def upsert(table: String, key: String, payload: String): SqlFragment =
    announcing(
      table,
      SqlFragment.raw(s"INSERT INTO $table (row_key, payload, updated_at) VALUES (") ++
        sql"$key, $payload" ++
        SqlFragment.raw(
          ", now()) ON CONFLICT (row_key) DO UPDATE SET " +
            "payload = EXCLUDED.payload, updated_at = now() RETURNING row_key"
        )
    )

  /** Removes a row, announced when there was one to remove. */
  def delete(table: String, key: String): SqlFragment =
    announcing(
      table,
      SqlFragment.raw(s"DELETE FROM $table WHERE row_key = ") ++ sql"$key" ++
        SqlFragment.raw(" RETURNING row_key")
    )

  /** Announces that `table` was emptied for a rebuild, in the rebuild's own transaction. */
  def announceRebuilt(table: String): SqlFragment =
    SqlFragment.raw(s"SELECT pg_notify('$Channel', '$table|$Rebuilt')")

  def selectByKey(table: String, key: String): SqlFragment =
    SqlFragment.raw(s"SELECT payload FROM $table WHERE row_key = ") ++ sql"$key"

  def selectWhere(table: String, condition: SqlFragment, limit: Int): SqlFragment =
    val head = SqlFragment.raw(s"SELECT payload FROM $table")
    val body = if condition.isEmpty then head else head ++ SqlFragment.raw(" WHERE ") ++ condition
    body ++ SqlFragment.raw(s" LIMIT $limit")

  def selectOrdered(
      table: String,
      condition: SqlFragment,
      order: SqlFragment,
      limit: Int
  ): SqlFragment =
    val head = SqlFragment.raw(s"SELECT payload FROM $table")
    val body = if condition.isEmpty then head else head ++ SqlFragment.raw(" WHERE ") ++ condition
    body ++ SqlFragment.raw(" ORDER BY ") ++ order ++ SqlFragment.raw(s" LIMIT $limit")

  /** Every row matching `condition`, with no limit: what a stream reads. */
  def selectRows(table: String, condition: SqlFragment): SqlFragment =
    val head = SqlFragment.raw(s"SELECT payload FROM $table")
    if condition.isEmpty then head else head ++ SqlFragment.raw(" WHERE ") ++ condition

  /** Every row matching `condition`, in `order`, with no limit: what a stream reads. */
  def selectRowsOrdered(table: String, condition: SqlFragment, order: SqlFragment): SqlFragment =
    selectRows(table, condition) ++ SqlFragment.raw(" ORDER BY ") ++ order

  def countWhere(table: String, condition: SqlFragment): SqlFragment =
    val head = SqlFragment.raw(s"SELECT count(*) FROM $table")
    if condition.isEmpty then head else head ++ SqlFragment.raw(" WHERE ") ++ condition
