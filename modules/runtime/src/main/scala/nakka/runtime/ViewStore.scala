package nakka.runtime

import nakka.runtime.SqlSyntax.sql

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
private[nakka] object ViewStore:

  def createTable(table: String): SqlFragment =
    SqlFragment.raw(
      s"""CREATE TABLE IF NOT EXISTS $table (
         |  row_key    TEXT PRIMARY KEY,
         |  payload    TEXT NOT NULL,
         |  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
         |)""".stripMargin
    )

  /**
   * Insert-or-replace. Uses `EXCLUDED.payload` rather than binding the payload twice, because
   * parameters are numbered by position and a repeated placeholder would need the value bound
   * again.
   */
  def upsert(table: String, key: String, payload: String): SqlFragment =
    SqlFragment.raw(s"INSERT INTO $table (row_key, payload, updated_at) VALUES (") ++
      sql"$key, $payload" ++
      SqlFragment.raw(
        ", now()) ON CONFLICT (row_key) DO UPDATE SET " +
          "payload = EXCLUDED.payload, updated_at = now()"
      )

  def delete(table: String, key: String): SqlFragment =
    SqlFragment.raw(s"DELETE FROM $table WHERE row_key = ") ++ sql"$key"

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

  def countWhere(table: String, condition: SqlFragment): SqlFragment =
    val head = SqlFragment.raw(s"SELECT count(*) FROM $table")
    if condition.isEmpty then head else head ++ SqlFragment.raw(" WHERE ") ++ condition
