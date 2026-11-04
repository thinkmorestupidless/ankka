package com.thinkmorestupidless.ankka.controlplane.tenancy

import com.typesafe.config.Config

/**
 * Whether the installation erases personal data (feature 042), from `ankka.controlplane.erasure`
 * (`ANKKA_ERASURE`, `on` or `off`), and how many keyring instances hold its keys
 * (`ANKKA_KEYRING_INSTANCES`). Read once at startup, as `BrokerFacts` is. Off unless the keyring's
 * component says otherwise; a service that declares personal fields is refused while it is.
 */
final case class ErasureSettings(
    enabled: Boolean,
    keyringInstances: Int,
    /** How long after an application the sweep asks every handler to run again (FR-015). */
    rerunWindow: scala.concurrent.duration.FiniteDuration = ErasureSettings.DefaultRerunWindow
)

object ErasureSettings:

  val Section: String        = "ankka.controlplane.erasure"
  val EnabledKey: String     = s"$Section.enabled"
  val InstancesKey: String   = s"$Section.keyring-instances"
  val RerunWindowKey: String = s"$Section.rerun-window"

  /** Thirty days, unless `ANKKA_ERASURE_RERUN_WINDOW` says otherwise. */
  val DefaultRerunWindow: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(30, java.util.concurrent.TimeUnit.DAYS)

  /** What a control plane told nothing reports: off, with the keyring's default size. */
  val off: ErasureSettings = ErasureSettings(enabled = false, keyringInstances = 2)

  /** Refuses, naming the key and the variable, a setting that is neither `on` nor `off`. */
  def from(config: Config): ErasureSettings =
    def text(key: String) = if config.hasPath(key) then config.getString(key).trim else ""
    val enabled           = text(EnabledKey).toLowerCase match
      case "" | "off" => false
      case "on"       => true
      case other      =>
        throw IllegalArgumentException(
          s"$EnabledKey (ANKKA_ERASURE) is '$other'; it must be 'on' or 'off'"
        )
    val instances = text(InstancesKey) match
      case "" => off.keyringInstances
      case n  =>
        n.toIntOption.filter(_ >= 1).getOrElse {
          throw IllegalArgumentException(
            s"$InstancesKey (ANKKA_KEYRING_INSTANCES) is '$n'; it must be a whole number of at least 1"
          )
        }
    val window = text(RerunWindowKey) match
      case "" => DefaultRerunWindow
      case w  =>
        scala.util
          .Try(config.getDuration(RerunWindowKey))
          .toOption
          .filter(d => !d.isNegative && !d.isZero)
          .map(d => scala.concurrent.duration.FiniteDuration(d.toMillis, "ms").toCoarsest)
          .getOrElse {
            throw IllegalArgumentException(
              s"$RerunWindowKey (ANKKA_ERASURE_RERUN_WINDOW) is '$w'; it must be a duration " +
                "longer than nothing, such as 30d"
            )
          }
    ErasureSettings(enabled, instances, window)
