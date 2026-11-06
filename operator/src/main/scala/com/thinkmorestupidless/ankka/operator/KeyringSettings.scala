package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.core.PlatformVariables

/**
 * Where the installation's keyring is, when erasure is on (feature 042), as the keyring's component
 * tells the operator:
 *
 *   - `address`: the keyring's door, given to the platform container of every service that declares
 *     personal fields as `ANKKA_KEYRING_ADDRESS`
 *
 * `None` in `Settings.erasure` is an installation with erasure off: nothing is rendered for it, and
 * the control plane refuses every descriptor that declares personal fields.
 */
final case class KeyringSettings(address: String)

object KeyringSettings:

  /** The namespace the keyring's component installs it in. */
  val Namespace: String = "ankka-keyring"

  /** The keyring's own name, as a platform workload: `ankka://platform/keyring`. */
  val Component: String = "keyring"

  /** The label a project's namespace carries while one of its services declares personal fields. */
  val PersonalLabel: String = "ankka.cloud/personal"

  /**
   * `ANKKA_ERASURE` is `on` or `off` (or unset, which is off), as the control plane reads it; `on`
   * needs the keyring's address beside it. Anything else is a component installed by halves, and
   * the operator refuses to start rather than guess, naming what is missing.
   */
  def read(lookup: (String, String) => Option[String]): Option[KeyringSettings] =
    val erasure = PlatformVariables.Erasure
    val address = PlatformVariables.KeyringAddress
    lookup("ankka.operator.erasure", erasure).map(_.trim.toLowerCase).getOrElse("") match
      case "" | "off" => None
      case "on"       =>
        lookup("ankka.operator.keyring-address", address).map(_.trim).filter(_.nonEmpty) match
          case Some(named) => Some(KeyringSettings(named))
          case None        =>
            throw IllegalStateException(
              s"$erasure is on and $address is not set: erasure needs the keyring's component, " +
                "which sets both"
            )
      case other =>
        throw IllegalStateException(s"$erasure is '$other'; it must be 'on' or 'off'")
