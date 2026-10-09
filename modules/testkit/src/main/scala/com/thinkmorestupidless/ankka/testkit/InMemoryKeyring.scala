package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.core.personal.KeyResult
import com.thinkmorestupidless.ankka.runtime.erasure.*

import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * The keyring of a test JVM (FR-030): subject keys, tombstones, lookup keys and the erasure log, in
 * memory, answering every service a test kit starts through a channel of its own, exactly as the
 * installation's keyring answers a deployed service's. One per JVM (`shared`) unless a suite makes
 * its own, so every kit of one project reads the others' personal fields.
 *
 * A grant lets a service of one project read another's subject keys, as a grant with `decrypt` does
 * (spec 040); a refused fetch is recorded. `outage` makes every fetch fail as `Unavailable`.
 */
final class InMemoryKeyring:

  private val random     = SecureRandom()
  private val keys       = TrieMap.empty[(String, String), Array[Byte]]
  private val tombstones = TrieMap.empty[(String, String), String]
  private val lookups    = TrieMap.empty[String, Array[Byte]]
  private val sequence   = AtomicLong()
  @volatile private var log: Vector[(String, LogEntry)]            = Vector.empty
  @volatile private var channels: Vector[Channel]                  = Vector.empty
  @volatile private var grants: Set[(String, String)]              = Set.empty
  @volatile private var refusals: Vector[(String, String, String)] = Vector.empty
  private val acks              = TrieMap.empty[String, Vector[String]]
  private val completions       = TrieMap.empty[String, Vector[(String, Completion)]]
  @volatile var outage: Boolean = false
  @volatile var minted: Int     = 0

  /** A new channel, for one service instance. */
  def connect(): KeyringConnection = Channel()

  /** Lets services of `reader` read `owner`'s subject keys, as a `decrypt` grant does. */
  def grant(reader: String, owner: String): Unit = synchronized {
    grants = grants + ((reader, owner))
  }

  def revoke(reader: String, owner: String): Unit = synchronized {
    grants = grants - ((reader, owner))
  }

  /** Every fetch refused, as (reader's project, owner's project, subject). */
  def refused: Vector[(String, String, String)] = refusals

  def holdsKey(project: String, subject: String): Boolean = keys.contains((project, subject))

  /**
   * Erases `subject` in `project`: the log written, the key destroyed and a tombstone kept, the
   * notice sent to every channel that reads the project, and the order to every service of it.
   * Answers the erasure's id.
   */
  def erase(project: String, subject: String): String =
    val (id, entry, targets) = synchronized {
      val seq   = sequence.incrementAndGet()
      val id    = s"erasure-$seq"
      val entry = LogEntry(id, seq, subject, Instant.now())
      log = log :+ (project -> entry)
      keys.remove((project, subject)): Unit
      tombstones.put((project, subject), id): Unit
      (id, entry, channels)
    }
    targets.filter(_.reads(project)).foreach(_.listener.foreach(_.destroyed(project, subject, id)))
    targets.filter(_.project.contains(project)).foreach { channel =>
      channel.listener.foreach(_.apply(ErasureOrder(id, entry.sequence, subject, reapply = false)))
    }
    id

  /**
   * Orders every service of the project to apply an erasure again, as the control plane's sweeper
   * does.
   */
  def reapply(project: String, erasureId: String): Unit =
    log.collectFirst { case (`project`, e) if e.erasureId == erasureId => e }.foreach { entry =>
      channels.filter(_.project.contains(project)).foreach { channel =>
        channel.listener.foreach(
          _.apply(ErasureOrder(entry.erasureId, entry.sequence, entry.subject, reapply = true))
        )
      }
    }

  /** The completions each service reported for an erasure, as (service, completion). */
  def completionsOf(erasureId: String): Vector[(String, Completion)] =
    completions.getOrElse(erasureId, Vector.empty)

  /** Waits until `services` completions of `erasureId` (counting repeats) have arrived. */
  def awaitCompletions(
      erasureId: String,
      services: Int,
      within: FiniteDuration = 30.seconds
  ): Vector[(String, Completion)] =
    AnkkaTestKit.eventually(s"$services completions of $erasureId", within)(
      Option(completionsOf(erasureId)).filter(_.size >= services)
    )

  def acknowledgements(erasureId: String): Vector[String] = acks.getOrElse(erasureId, Vector.empty)

  private def unavailable() =
    CommandError("the keyring is unavailable: a test is holding it down", ErrorCode.Unavailable)

  private final class Channel extends KeyringConnection:
    @volatile var hello: Option[Hello]              = None
    @volatile var listener: Option[KeyringListener] = None
    def project: Option[String]                     = hello.map(_.project)
    def reads(other: String): Boolean = hello.exists(h =>
      h.project == other || (h.reads.contains(other) && grants((h.project, other)))
    )

    def fetch(owner: String, subject: String, create: Boolean): KeyResult =
      if outage then throw unavailable()
      val reader = project.getOrElse(owner)
      if reader != owner && !grants((reader, owner)) then
        synchronized { refusals = refusals :+ ((reader, owner, subject)) }
        KeyResult.Refused(s"project $reader holds no grant that allows decryption of $owner")
      else
        tombstones.get((owner, subject)) match
          case Some(id) => KeyResult.Destroyed(id)
          case None =>
            keys.get((owner, subject)) match
              case Some(key) => KeyResult.Available(key)
              case None if create && reader == owner =>
                KeyResult.Available(
                  keys.getOrElseUpdate((owner, subject), { minted += 1; fresh() })
                )
              case None => KeyResult.Unknown

    def lookupKey(owner: String): Array[Byte] =
      if outage then throw unavailable()
      lookups.getOrElseUpdate(owner, fresh())

    def open(hello: Hello, listener: KeyringListener): Unit =
      this.hello = Some(hello)
      this.listener = Some(listener)
      InMemoryKeyring.this.synchronized { channels = channels :+ this }
      val pending = log.collect {
        case (p, e) if p == hello.project && hello.appliedUpTo.forall(e.sequence > _) => e
      }
      listener.log(pending)

    def ack(erasureId: String): Unit =
      acks.updateWith(erasureId)(prior =>
        Some(prior.getOrElse(Vector.empty) :+ hello.fold("")(_.service))
      ): Unit

    def completed(completion: Completion): Unit =
      completions.updateWith(completion.erasureId)(prior =>
        Some(prior.getOrElse(Vector.empty) :+ (hello.fold("")(_.service) -> completion))
      ): Unit

    def close(): Unit = InMemoryKeyring.this.synchronized {
      channels = channels.filterNot(_ eq this)
    }

  private def fresh(): Array[Byte] =
    val key = new Array[Byte](32)
    random.nextBytes(key)
    key

object InMemoryKeyring:
  /** The keyring every test kit in this JVM uses unless a suite gives it another. */
  lazy val shared: InMemoryKeyring = InMemoryKeyring()
