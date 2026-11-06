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
  private val made                  = mutable.Map.empty[(String, String), Int]
  private var down: Option[Instant] = None

  private def unavailable(): Nothing =
    throw CommandError(
      "the keyring is unavailable: the test kit's keyring is in an outage",
      ErrorCode.Unavailable,
      Map(Personal.ErrorKey -> Personal.Unavailable)
    )

  private def checkUp(): Unit =
    val ended = synchronized {
      down match
        case Some(until) if clock.instant().isBefore(until) => unavailable()
        case Some(_)                                        =>
          down = None
          true
        case None => false
    }
    if ended then release()

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
            made(at) = made.getOrElse(at, 0) + 1
            KeyAnswer.Key(k)
          // A subject never written has no key, and nothing sealed under one: it reads as erased.
          case None => KeyAnswer.Absent
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
    subscribed.trySuccess(()): Unit
    () => listeners.remove(entry): Unit

  private val subscribed = scala.concurrent.Promise[Unit]()

  /** At once after the first subscription, which hands its backlog before it returns. */
  def caughtUp: scala.concurrent.Future[Unit] = subscribed.future

  // Acknowledgements made in an outage: the instance's client holds them until the keyring is back.
  private val held = mutable.ArrayBuffer.empty[(KeyringAck, scala.concurrent.Promise[Unit])]

  /** Received at once, or in an outage held until it ends, as a client holds an unsent ack. */
  def ack(ack: KeyringAck): scala.concurrent.Future[Unit] = synchronized {
    if down.exists(until => clock.instant().isBefore(until)) then
      val sent = scala.concurrent.Promise[Unit]()
      held += ack -> sent
      sent.future
    else
      acked.add(ack): Unit
      scala.concurrent.Future.unit
  }

  private def release(): Unit =
    val now = synchronized {
      val all = held.toVector
      held.clear()
      all
    }
    now.foreach { (ack, sent) =>
      acked.add(ack)
      sent.trySuccess(()): Unit
    }

  /** Confirmed now, as an in-process channel always is, except in an outage: since it began. */
  def confirmedAt(): Option[Instant] = synchronized {
    down match
      case Some(until) if clock.instant().isBefore(until) => downSince
      case _                                              => Some(clock.instant())
  }

  private var downSince: Option[Instant] = None

  private def fresh(): IArray[Byte] =
    val k = new Array[Byte](32)
    random.nextBytes(k)
    IArray.unsafeFromArray(k)

  // ── what a test does ────────────────────────────────────────────────────────

  /**
   * Destroys `subject`'s key in `project` now and sends the notice to every listener; answers the
   * notice's sequence. `rerun` sends a notice again for a subject already erased. Refused as
   * unavailable in an outage, as a stopped keyring refuses the control plane.
   */
  def erase(project: String, subject: DataSubject, rerun: Boolean = false): Long =
    // A keyring that cannot be reached destroys nothing: the erasure waits for its return.
    checkUp()
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
    downSince = Some(clock.instant())
  }

  /** Ends an outage now; the acknowledgements held through it arrive. */
  def recover(): Unit =
    synchronized { down = None }
    release()

  /** The acknowledgements held in an outage, not yet received. */
  def heldAcks: Vector[KeyringAck] = synchronized(held.map(_._1).toVector)

  /**
   * Forgets the acknowledgements held in an outage, as an instance that stopped before the keyring
   * came back loses the ones its client had not sent.
   */
  def forgetHeldAcks(): Unit = synchronized(held.clear())

  /** How many times `subject`'s key was asked for in `project`. */
  def fetches(project: String, subject: DataSubject): Int =
    synchronized(fetched.getOrElse((project, subject.value), 0))

  /**
   * How many subject keys were ever made for `subject`: one, however many instances wrote first.
   */
  def keysMade(project: String, subject: DataSubject): Int =
    synchronized(made.getOrElse((project, subject.value), 0))

  /** Whether `subject` has a key in `project` now. */
  def holdsKey(project: String, subject: DataSubject): Boolean =
    synchronized(keys.contains((project, subject.value)))

  /** Every acknowledgement received, in order. */
  def acks: Vector[KeyringAck] = acked.asScala.toVector

  /** Every notice sent, in order. */
  def sent: Vector[KeyringNotice] = synchronized(notices.toVector)
