package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  DataSubject,
  ErrorCode,
  KeyAnswer,
  Personal
}
import com.thinkmorestupidless.ankka.core.keyring.{KeyringAck, KeyringNotice}
import com.thinkmorestupidless.ankka.runtime.keyring.KeyStoreClient

import java.security.SecureRandom
import java.time.{Clock, Instant}
import java.util.concurrent.CopyOnWriteArrayList
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * A keyring in memory, for `AnkkaTestKit` (feature 042): subject keys made on first write, an
 * erasure that destroys one at once and sends the notice, an outage a test can start, and a count
 * of fetches per subject. One per kit, kept across `restartService`, as the installation's keyring
 * outlives a service's restart.
 */
final class InMemoryKeyring(clock: Clock = Clock.systemUTC()) extends KeyStoreClient:

  private val random                = SecureRandom()
  private val keys                  = mutable.Map.empty[(String, String), IArray[Byte]]
  private val tombstones            = mutable.Map.empty[(String, String), Long]
  private val lookup                = mutable.Map.empty[String, IArray[Byte]]
  private val notices               = mutable.ArrayBuffer.empty[KeyringNotice]
  private val listeners             = CopyOnWriteArrayList[(Long, KeyringNotice => Unit)]()
  private val acked                 = CopyOnWriteArrayList[KeyringAck]()
  private val fetched               = mutable.Map.empty[(String, String), Int]
  private var down: Option[Instant] = None

  private def unavailable(): Nothing =
    throw CommandError(
      "the keyring is unavailable: the test kit's keyring is in an outage",
      ErrorCode.Unavailable,
      Map(Personal.ErrorKey -> Personal.Unavailable)
    )

  private def checkUp(): Unit = synchronized {
    down.foreach(until => if clock.instant().isBefore(until) then unavailable() else down = None)
  }

  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    checkUp()
    synchronized {
      val at = (project, subject.value)
      fetched(at) = fetched.getOrElse(at, 0) + 1
      if tombstones.contains(at) then KeyAnswer.Destroyed
      else
        keys.get(at) match
          case Some(k)        => KeyAnswer.Key(k)
          case None if create =>
            val k = fresh()
            keys(at) = k
            KeyAnswer.Key(k)
          // A subject never written has no key, and nothing sealed under one: it reads as erased.
          case None => KeyAnswer.Destroyed
    }

  def lookupKey(project: String): IArray[Byte] =
    checkUp()
    synchronized(lookup.getOrElseUpdate(project, fresh()))

  def subscribe(after: Long)(listener: KeyringNotice => Unit): AutoCloseable =
    val entry   = (after, listener)
    val backlog = synchronized {
      listeners.add(entry)
      notices.filter(_.seq > after).toVector
    }
    backlog.foreach(listener)
    () => listeners.remove(entry): Unit

  def ack(ack: KeyringAck): Unit = acked.add(ack): Unit

  private def fresh(): IArray[Byte] =
    val k = new Array[Byte](32)
    random.nextBytes(k)
    IArray.unsafeFromArray(k)

  // ── what a test does ────────────────────────────────────────────────────────

  /**
   * Destroys `subject`'s key in `project` now and sends the notice to every listener; answers the
   * notice's sequence. `rerun` sends a notice again for a subject already erased.
   */
  def erase(project: String, subject: DataSubject, rerun: Boolean = false): Long =
    val notice = synchronized {
      val at = (project, subject.value)
      keys.remove(at)
      val seq = notices.size.toLong + 1
      tombstones.getOrElseUpdate(at, seq)
      val n = KeyringNotice(seq, project, subject.value, rerun)
      notices += n
      n
    }
    listeners.asScala.foreach(_._2(notice))
    notice.seq

  /** Refuses every request as unavailable for `duration`, by the keyring's clock. */
  def outage(duration: FiniteDuration): Unit = synchronized {
    down = Some(clock.instant().plusMillis(duration.toMillis))
  }

  /** Ends an outage now. */
  def recover(): Unit = synchronized { down = None }

  /** How many times `subject`'s key was asked for in `project`. */
  def fetches(project: String, subject: DataSubject): Int =
    synchronized(fetched.getOrElse((project, subject.value), 0))

  /** Whether `subject` has a key in `project` now. */
  def holdsKey(project: String, subject: DataSubject): Boolean =
    synchronized(keys.contains((project, subject.value)))

  /** Every acknowledgement received, in order. */
  def acks: Vector[KeyringAck] = acked.asScala.toVector

  /** Every notice sent, in order. */
  def sent: Vector[KeyringNotice] = synchronized(notices.toVector)
