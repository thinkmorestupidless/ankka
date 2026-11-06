package com.thinkmorestupidless.ankka.runtime.keyring

import scala.collection.mutable

/**
 * The last erasure notice an instance may say it carried out (feature 042, R15): the highest one
 * handed to it such that every notice handed before it was carried out too. A notice whose duties
 * failed holds it back, however many later ones succeed, so a hello from here is handed the failed
 * one again rather than skipping it.
 *
 * A project's notices are not numbered one after another (the erasure log's numbers are the whole
 * installation's), so "contiguous" means over the notices handed here, in order, not over the
 * integers. A notice at or below the mark (a re-run, or one handed again) changes nothing.
 */
final class CarriedThrough(start: Long = 0L):

  private var through = start
  // Handed and not yet carried out.
  private val waiting = mutable.TreeSet.empty[Long]
  // Carried out above the mark, held back by one still waiting below them.
  private val done = mutable.TreeSet.empty[Long]

  /** The mark: every notice handed here up to it has been carried out. */
  def value: Long = synchronized(through)

  /** Raises the mark to `seq` when it is higher: what the service's own database records. */
  def atLeast(seq: Long): Unit = synchronized {
    if seq > through then
      through = seq
      waiting.filterInPlace(_ > seq)
      done.filterInPlace(_ > seq)
      advance()
  }

  /** Notice `seq` was handed to the instance. */
  def handed(seq: Long): Unit = synchronized {
    if seq > through && !done.contains(seq) then waiting += seq: Unit
  }

  /** Notice `seq` was carried out; answers the mark after it. */
  def carried(seq: Long): Long = synchronized {
    waiting -= seq
    if seq > through then done += seq
    advance()
    through
  }

  private def advance(): Unit =
    val below = waiting.headOption.getOrElse(Long.MaxValue)
    val ready = done.rangeUntil(below)
    if ready.nonEmpty then
      through = ready.last
      done --= ready.toVector
