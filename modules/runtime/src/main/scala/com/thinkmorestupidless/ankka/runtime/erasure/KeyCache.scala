package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.core.personal.{KeyResult, PersonalScope}

import scala.concurrent.duration.*

/**
 * How long an instance keeps what the keyring told it (FR-020): at most `maxKeys` answers, each for
 * `expiry` after it was fetched while the keyring answers; through an outage, every cached answer
 * for `outageBound` from the first failed fetch, and then none; and a destroyed key dropped the
 * moment the notice arrives.
 */
final case class KeyCacheSettings(
    maxKeys: Int = 10000,
    expiry: FiniteDuration = 5.minutes,
    outageBound: FiniteDuration = 15.minutes
)

object KeyCacheSettings:
  def from(config: com.typesafe.config.Config): KeyCacheSettings =
    def duration(key: String, default: FiniteDuration) =
      if config.hasPath(key) then config.getDuration(key).toMillis.millis else default
    KeyCacheSettings(
      if config.hasPath("ankka.erasure.cache.keys") then config.getInt("ankka.erasure.cache.keys")
      else 10000,
      duration("ankka.erasure.cache.expiry", 5.minutes),
      duration("ankka.erasure.cache.outage-bound", 15.minutes)
    )

final class KeyCache(
    settings: KeyCacheSettings,
    now: () => Long = () => System.currentTimeMillis()
):

  private final case class Entry(result: KeyResult, fetchedAt: Long)

  private val entries =
    new java.util.LinkedHashMap[(String, String), Entry](256, 0.75f, true):
      override def removeEldestEntry(
          eldest: java.util.Map.Entry[(String, String), Entry]
      ): Boolean =
        this.size() > settings.maxKeys

  /** When the first fetch of the current outage failed; `None` while the keyring answers. */
  private var outageSince: Option[Long] = None

  /**
   * The answer for a subject: a fresh cached one, else a fetch. While the keyring does not answer,
   * a cached answer of any age is served until the outage has lasted `outageBound`, when every
   * cached answer is dropped and the fetch's failure is thrown.
   */
  def get(project: String, subject: String, fetch: () => KeyResult): KeyResult =
    val at     = now()
    val cached = synchronized(Option(entries.get((project, subject))))
    cached match
      case Some(entry) if at - entry.fetchedAt <= settings.expiry.toMillis => entry.result
      case _ =>
        try
          val result = fetch()
          synchronized {
            outageSince = None
            result match
              case KeyResult.Refused(_) | KeyResult.Unknown => ()
              case other => entries.put((project, subject), Entry(other, at)): Unit
          }
          result match
            case KeyResult.Destroyed(_) => PersonalScope.markDestroyed(project, subject)
            case _                      => ()
          result
        catch
          case unavailable: CommandError if unavailable.code == ErrorCode.Unavailable =>
            synchronized {
              val since = outageSince.getOrElse { outageSince = Some(at); at }
              if at - since > settings.outageBound.toMillis then
                entries.clear()
                throw unavailable
              else
                Option(entries.get((project, subject))).map(_.result).getOrElse(throw unavailable)
            }

  /**
   * A fetch that must reach the keyring whatever is cached: the first write of a never-written
   * subject.
   */
  def refresh(project: String, subject: String, fetch: () => KeyResult): KeyResult =
    synchronized(entries.remove((project, subject))): Unit
    get(project, subject, fetch)

  /**
   * The keyring said this key is destroyed: no cached copy survives the call, and every value this
   * JVM holds in memory of the subject reads as erased from now on.
   */
  def destroyed(project: String, subject: String, erasureId: String): Unit =
    synchronized(
      entries.put((project, subject), Entry(KeyResult.Destroyed(erasureId), now()))
    ): Unit
    PersonalScope.markDestroyed(project, subject)

  /**
   * Everything forgotten — the keyring closed the channel before this instance could confirm a
   * notice.
   */
  def clear(): Unit = synchronized(entries.clear())

  def size: Int = synchronized(entries.size())
