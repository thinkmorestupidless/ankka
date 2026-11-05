package com.thinkmorestupidless.ankka.runtime.keyring

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

/**
 * What `ankka.keyring` says (feature 042): where the keyring is, and how a service's instance holds
 * the keys it fetched from it.
 *
 * `address` is `None` when nothing names a keyring, which is every service that declares no
 * personal fields: such a service reaches no keyring, and a personal field it meets fails as
 * Unavailable.
 */
final case class KeyringSettings(
    address: Option[String],
    maxKeys: Int,
    ttl: FiniteDuration,
    outageBound: FiniteDuration,
    noticeBound: FiniteDuration,
    fetchTimeout: FiniteDuration,
    /** How long a key of another project is held: no notice of that project reaches this one. */
    foreignTtl: FiniteDuration = FiniteDuration(30, java.util.concurrent.TimeUnit.SECONDS)
)

object KeyringSettings:

  /**
   * Reads the settings. Throws `IllegalArgumentException` naming the setting when a bound is not
   * positive, or when the cache's outage bound exceeds the notice bound: an instance that cannot
   * hear the keyring drops its keys at the notice bound, so a longer outage bound would promise
   * what erasure forbids (FR-020, FR-022; decided 2026-11-05, the notice bound wins).
   */
  def from(config: Config): KeyringSettings =
    val c                                      = config.getConfig("ankka.keyring")
    def positive(path: String): FiniteDuration =
      val d = c.getDuration(path).toScala
      if d <= Duration.Zero then
        throw IllegalArgumentException(s"ankka.keyring.$path must be positive, was $d")
      d
    val maxKeys = c.getInt("cache.max-keys")
    if maxKeys < 1 then
      throw IllegalArgumentException(
        s"ankka.keyring.cache.max-keys must be at least 1, was $maxKeys"
      )
    val settings = KeyringSettings(
      address = Option(c.getString("address")).map(_.trim).filter(_.nonEmpty),
      maxKeys = maxKeys,
      ttl = positive("cache.ttl"),
      outageBound = positive("cache.outage-bound"),
      noticeBound = positive("notice-bound"),
      fetchTimeout = positive("fetch-timeout"),
      foreignTtl = positive("cache.foreign-ttl")
    )
    if settings.outageBound > settings.noticeBound then
      throw IllegalArgumentException(
        s"ankka.keyring.cache.outage-bound (${settings.outageBound}) must not exceed " +
          s"ankka.keyring.notice-bound (${settings.noticeBound}): an instance that cannot hear " +
          "the keyring's erasure notices drops every key it holds at the notice bound"
      )
    settings
