package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.ComponentId

/**
 * A question a view can be asked by name: one SQL statement over the view's own table, whose values
 * are the `:name`s it holds.
 *
 * Declared on the view's companion and kept as a `val`, as a command is, so a caller asks for it by
 * the handle rather than by a string. The statement is checked when the service starts: one that is
 * not a single read of the view's own table stops the service, naming the view and the query, so
 * the database is never sent it. A value is bound as a parameter and never becomes part of the
 * text.
 *
 * {{{
 * val under = query("under")(s"""
 *   WITH RECURSIVE under AS (
 *     SELECT row_key, payload FROM $table WHERE payload::jsonb->>'parent' = :row
 *     UNION ALL
 *     SELECT n.row_key, n.payload FROM $table n JOIN under u ON n.payload::jsonb->>'parent' = u.row_key
 *   )
 *   SELECT payload FROM under""")
 * }}}
 *
 * The answer is rows of the view's own row type, read from the statement's `payload` column.
 *
 * A query declared `.watched` can also be watched: its statement selects `row_key` beside
 * `payload`, has no limit and does not aggregate, because a watch decides each written row alone.
 * That too is checked when the service starts.
 */
final case class DeclaredQuery(
    view: ComponentId,
    name: String,
    statement: String,
    watchable: Boolean = false
)
