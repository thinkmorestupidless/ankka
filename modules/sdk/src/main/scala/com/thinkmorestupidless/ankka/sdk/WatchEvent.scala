package com.thinkmorestupidless.ankka.sdk

/**
 * What a watcher of a view is given.
 *
 * A watch first gives every row the watched query matches now, then [[WatchEvent.CaughtUp]] once,
 * then, for as long as the watcher reads, each row that is written and matches and a removal for
 * each row it was given that is written so it no longer matches, or is deleted. A watch is live and
 * not a record: rows are coalesced per key, so a row written faster than the watcher reads reaches
 * it fewer times than it was written, never an older version after a newer one, and what was
 * written while nobody watched is not replayed.
 */
enum WatchEvent[+Row]:

  /** The row under `key`, as the watched query sees it now. */
  case Row(key: String, row: Row)

  /** The row under `key`, given earlier, no longer matches or was deleted. */
  case Removed(key: String)

  /** Given once, after the rows the query matched when the watch began, and before any change. */
  case CaughtUp

/**
 * How a watch holds rows for a watcher that has not read them.
 *
 * At most `unread` rows are held, one per row key: a row written again while unread replaces the
 * one held. `None` is the instance's `ankka.view.unread-bound`. When the watch holds as many as its
 * bound and a row for another key arrives, `overflow` decides.
 */
final case class Watching(unread: Option[Int] = None, overflow: Overflow = Overflow.DropHead):
  unread.foreach(n => require(n >= 1, s"a watch's unread bound is 1 or more, not $n"))

/**
 * What a watch does when it holds as many unread rows as its bound and another key's row arrives.
 * No strategy slows the view: a watcher never holds back the view's writes.
 */
enum Overflow:

  /** Drop the row changed longest ago; the default. */
  case DropHead

  /** Drop the row changed most recently. */
  case DropTail

  /** Drop the row that arrived. */
  case DropNew

  /** Drop every unread row. */
  case DropAll

  /**
   * End the watch with [[WatchEnd.Unread]], for a watcher that would rather know than miss rows.
   */
  case Fail

/** Why a watch ended, other than its watcher no longer reading. */
enum WatchEnd(val wire: String, val sentence: String):
  case Rebuilt extends WatchEnd("rebuilt", "the view was emptied for a rebuild")
  case InstanceStopping
      extends WatchEnd("instance-stopping", "the instance serving the watch stopped")
  case ListenerLost
      extends WatchEnd("listener-lost", "the instance lost its connection for view changes")
  case Unread
      extends WatchEnd("unread", "the watcher did not read, and its unread bound was reached")

object WatchEnd:

  /** The reason a wire word names, as a process is sent it. */
  def fromWire(word: String): Option[WatchEnd] = values.find(_.wire == word)

/**
 * A watch ended for `reason`. A watcher that still wants the rows watches again, and is given the
 * rows the query matches now.
 */
final case class WatchEnded(reason: WatchEnd) extends RuntimeException(reason.sentence)
