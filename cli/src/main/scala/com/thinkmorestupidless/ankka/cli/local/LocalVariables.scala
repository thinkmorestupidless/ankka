package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.core.PlatformVariables

/**
 * The variables `ankka local up` and `ankka local run` set themselves (feature 066, research R9):
 * where a service's database and broker are, its name, ports and secret key, how its hosting's
 * parts find each other. Nobody else may give one, and a local value that does is refused, naming
 * the service and the variable. One value, read by the plan that sets them and by the refusal, so
 * the two cannot differ.
 *
 * It is the platform's own list (`PlatformVariables.PlatformOnly`, which a descriptor may not give
 * either) and the names and prefixes of what a cluster provisions for a service, which a descriptor
 * may give on an installation (its own database, its own broker) but which on a developer's machine
 * are the command's.
 */
object LocalVariables:

  /** Exact names, beyond the platform's own. */
  val Names: Set[String] =
    PlatformVariables.PlatformOnly ++ Set(
      PlatformVariables.ServiceName,
      PlatformVariables.SecretKey,
      PlatformVariables.WebPort,
      PlatformVariables.ServicesUrl,
      "ANKKA_DATABASE"
    )

  /** Every name with one of these in front. */
  val Prefixes: Vector[String] =
    PlatformVariables.SharedPrefixes ++ PlatformVariables.RuntimeReadPrefixes :+ "ANKKA_DB_"

  def isCommandOwned(name: String): Boolean =
    Names.contains(name) || Prefixes.exists(name.startsWith)

  /**
   * What a descriptor may set on an installation and is not used on this machine: its own database
   * or broker.
   */
  def isInstallationOnly(name: String): Boolean =
    name.startsWith("ANKKA_DB_") || PlatformVariables.shared(name)
