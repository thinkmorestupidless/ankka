package com.thinkmorestupidless.ankka.operator

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a project database's archive can be recovered to a moment (feature 041). PostgreSQL stops
 * a recovery to a time at the first commit after it, and a recovery that replays the whole archive
 * without meeting one fails: "recovery ended before configured recovery target was reached". The
 * archive's lag says only when a segment was last sent, not what it held, so a moment a few seconds
 * old could be past every commit the archive had. Before a restore or a rehearsal is rendered, the
 * source writes a commit of its own (an empty transaction given an id), switches to a new segment
 * and waits for that one to be archived: every moment before the commit can then be reached.
 *
 * The segment is remembered per restore, so the commit is written once; after a restart of the
 * operator it may be written again, which costs one empty transaction.
 */
object RecoveryPoint:

  private val marked = new ConcurrentHashMap[String, String]()

  val Commit: String   = DatabaseQueries.Recovery.Commit
  val Switch: String   = DatabaseQueries.Recovery.Switch
  val Archived: String = DatabaseQueries.Recovery.Archived

  /**
   * Whether a recovery of `name` to `moment` can begin. `ask` runs one statement on the source's
   * primary as `postgres` and answers its output, or nothing when it could not.
   */
  def reached(
      key: String,
      moment: Instant,
      now: Instant,
      ask: String => Option[String]
  ): Boolean =
    // The commit must come after the moment; a moment not yet passed waits for it.
    moment.isBefore(now) && {
      val segment = Option(marked.get(key)).orElse {
        // Two statements, two transactions: the commit is written before the switch, in the
        // segment the switch ends.
        ask(Commit).flatMap(_ => ask(Switch)).map(_.trim).filter(_.nonEmpty).map { s =>
          marked.put(key, s): Unit
          s
        }
      }
      // Segment names on one timeline sort as they were written.
      segment.exists(s => ask(Archived).map(_.trim).exists(a => a.nonEmpty && a >= s))
    }

  /** For a test: forget what was marked. */
  private[operator] def forget(): Unit = marked.clear()
