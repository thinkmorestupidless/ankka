package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

/**
 * The installation's cloud, as the operator reads it (feature 044): which provider fulfils its
 * cloud requests, the account and default location they are made in, the one wrapping key, how long
 * a request may go unacknowledged before the operator says so, and the rotation grace a provider
 * honours (read here only so a malformed one stops the operator rather than the provider).
 *
 * `None` on `Settings` is an installation with no cloud provider: no request is written, and a
 * service that would need one is served by the installation itself, exactly as before.
 */
final case class CloudSettings(
    provider: String,
    account: String,
    location: String,
    kmsKey: Option[String],
    acknowledgementBound: FiniteDuration,
    rotationGrace: FiniteDuration
)

object CloudSettings:

  val DefaultAcknowledgementBound: FiniteDuration = 2.minutes
  val DefaultRotationGrace: FiniteDuration        = 1.hour

  /** Each setting: its system property, then the variable the platform ConfigMap sets. */
  val Variables: Vector[(String, String)] = Vector(
    "ankka.operator.cloud-provider"              -> PlatformVariables.CloudProvider,
    "ankka.operator.cloud-account"               -> PlatformVariables.CloudAccount,
    "ankka.operator.cloud-location"              -> PlatformVariables.CloudLocation,
    "ankka.operator.cloud-kms-key"               -> PlatformVariables.CloudKmsKey,
    "ankka.operator.cloud-acknowledgement-bound" -> PlatformVariables.CloudAcknowledgementBound,
    "ankka.operator.cloud-rotation-grace"        -> PlatformVariables.CloudRotationGrace
  )

  /**
   * The cloud settings, or none. No provider, or `none`, is no cloud. A provider the platform does
   * not know, or one named without its account or location, refuses to start naming what is wrong:
   * an operator that guessed would write requests nothing answers, or answer them in the wrong
   * place.
   */
  def read(
      lookup: (String, String) => Option[String],
      known: Set[String] = PlatformVariables.CloudProviders
  ): Option[CloudSettings] =
    val Vector(provider, account, location, kmsKey, bound, grace) =
      Variables.map(lookup.tupled): @unchecked
    provider.filterNot(_ == PlatformVariables.CloudProviderNone).map { name =>
      if !known.contains(name) then
        throw IllegalStateException(
          s"${PlatformVariables.CloudProvider} is '$name', which the platform does not know; " +
            s"it is '${PlatformVariables.CloudProviderNone}' or one of " +
            known.toVector.sorted.mkString(", ")
        )
      def required(value: Option[String], variable: String): String =
        value.getOrElse(
          throw IllegalStateException(
            s"${PlatformVariables.CloudProvider} is '$name', so $variable must be set too"
          )
        )
      CloudSettings(
        provider = name,
        account = required(account, PlatformVariables.CloudAccount),
        location = required(location, PlatformVariables.CloudLocation),
        kmsKey = kmsKey,
        acknowledgementBound = bound
          .map(duration(_, PlatformVariables.CloudAcknowledgementBound))
          .getOrElse(DefaultAcknowledgementBound),
        rotationGrace = grace
          .map(duration(_, PlatformVariables.CloudRotationGrace))
          .getOrElse(DefaultRotationGrace)
      )
    }

  private val Duration = """(\d+)\s*([smh]?)""".r

  /**
   * `30s`, `2m`, `1h`, or a bare number of seconds. Anything else refuses to start: a malformed
   * bound that quietly became the default would be a setting that looked honoured and was not.
   */
  def duration(text: String, variable: String): FiniteDuration =
    text.trim match
      case Duration(n, "h")      => n.toLong.hours
      case Duration(n, "m")      => n.toLong.minutes
      case Duration(n, "s" | "") => n.toLong.seconds
      case other =>
        throw IllegalStateException(
          s"$variable is '$other'; it must be a whole number of seconds, minutes or hours, " +
            "such as 30s, 2m or 1h"
        )
