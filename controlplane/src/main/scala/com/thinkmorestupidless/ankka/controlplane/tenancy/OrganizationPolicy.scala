package com.thinkmorestupidless.ankka.controlplane.tenancy

import com.typesafe.config.Config

/** Who may create an organization in this installation. */
enum OrganizationCreation:
  /** Anyone logged in, who becomes its first owner — every installation before feature 011. */
  case Open

  /**
   * Holders of the `platform-admin` realm role only. A hosted installation, where being registered
   * and being entitled are different facts and the gap between them is a checkout the platform
   * knows nothing about: the product's own service account creates each organization for its owner,
   * and a registered user is refused.
   */
  case PlatformAdmin

object OrganizationCreation:
  val Key      = "ankka.controlplane.organizations.creation"
  val Variable = "ANKKA_ORGANIZATION_CREATION"

  def byName(text: String): Option[OrganizationCreation] = text.trim.toLowerCase match
    case "open"           => Some(Open)
    case "platform-admin" => Some(PlatformAdmin)
    case _                => None

/**
 * The installation's organization policy — `ankka.controlplane.organizations` in `reference.conf`,
 * read once at startup like `AuthConfig` and `DeployConfig`, and enforced by the organization
 * endpoint on creation and nowhere else. It is configuration, never state: no event carries it.
 */
final case class OrganizationPolicy(
    creation: OrganizationCreation,
    signupUrl: Option[String] = None
):

  /** The 403 a refused caller reads, verbatim, from the CLI. */
  def refusal: String =
    val where = signupUrl.fold("")(url => s"; sign up at $url")
    s"organizations in this installation are created by the platform administrator$where"

object OrganizationPolicy:

  /** Open, with nowhere to send anyone: byte for byte what every installation did before. */
  val default: OrganizationPolicy = OrganizationPolicy(OrganizationCreation.Open)

  /**
   * Refuses a value that is neither `open` nor `platform-admin` with the key, the variable and both
   * values in the message: a control plane that misread its policy as open because of a typo would
   * be giving organizations away, so it must not come up at all.
   */
  def from(config: Config): OrganizationPolicy =
    val section = config.getConfig("ankka.controlplane.organizations")
    val text    = section.getString("creation")
    val creation = OrganizationCreation
      .byName(text)
      .getOrElse(
        throw IllegalArgumentException(
          s"${OrganizationCreation.Key} (${OrganizationCreation.Variable}) is '$text'; " +
            "it must be 'open' or 'platform-admin'"
        )
      )
    val url = Option(section.getString("signup-url")).map(_.trim).filter(_.nonEmpty)
    OrganizationPolicy(creation, url)
