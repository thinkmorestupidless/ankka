package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer}

import java.time.{Clock, Duration as JDuration, Instant}
import scala.concurrent.duration.FiniteDuration

/**
 * The subject keys one instance holds (feature 042, R5): at most `maxKeys`, the least recently used
 * going first; each fresh for `ttl`, then asked for again; and, while the keyring cannot be
 * reached, still served until `outageBound` after it was fetched. A notice drops one; losing the
 * notices channel drops all.
 *
 * A destroyed key is held as `Destroyed`, which never changes; `Absent` is never held, since the
 * next write makes the key.
 */
final class KeyCache(maxKeys: Int, ttl: FiniteDuration, outageBound: FiniteDuration, clock: Clock):

  private final case class Held(answer: KeyAnswer, at: Instant)

  // Access order: the eldest entry is the least recently used.
  private val held =
    new java.util.LinkedHashMap[(String, String), Held](16, 0.75f, true):
      override def removeEldestEntry(e: java.util.Map.Entry[(String, String), Held]): Boolean =
        this.size > maxKeys

  private def age(h: Held): JDuration = JDuration.between(h.at, clock.instant())

  /** The answer held for `subject`, while it is fresh. */
  def fresh(project: String, subject: DataSubject): Option[KeyAnswer] = synchronized {
    Option(held.get((project, subject.value))).collect {
      case Held(d @ KeyAnswer.Destroyed, _)    => d
      case h if age(h).toMillis < ttl.toMillis => h.answer
    }
  }

  /** The answer held for `subject` while the keyring cannot be reached: within the outage bound. */
  def stale(project: String, subject: DataSubject): Option[KeyAnswer] = synchronized {
    Option(held.get((project, subject.value))).collect {
      case Held(d @ KeyAnswer.Destroyed, _)            => d
      case h if age(h).toMillis < outageBound.toMillis => h.answer
    }
  }

  /** Holds what the keyring answered, unless it was `Absent`. */
  def put(project: String, subject: DataSubject, answer: KeyAnswer): Unit = answer match
    case KeyAnswer.Absent => ()
    case other            =>
      synchronized(held.put((project, subject.value), Held(other, clock.instant()))): Unit

  /** Drops `subject`'s key, as a notice says to. */
  def drop(project: String, subject: DataSubject): Unit =
    synchronized(held.remove((project, subject.value))): Unit

  /** Drops every key, as losing the notices channel says to. */
  def dropAll(): Unit = synchronized(held.clear())

  /** How many keys are held. */
  def size: Int = synchronized(held.size)

object KeyCache:
  def apply(settings: KeyringSettings, clock: Clock = Clock.systemUTC()): KeyCache =
    new KeyCache(settings.maxKeys, settings.ttl, settings.outageBound, clock)
