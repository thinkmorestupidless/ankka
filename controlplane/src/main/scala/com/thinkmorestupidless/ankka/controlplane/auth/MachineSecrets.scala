package com.thinkmorestupidless.ankka.controlplane.auth

/**
 * A machine's client secret (feature 040): 64 hex characters from 256 random bits, shown once when
 * the machine is registered and kept only as its SHA-256 digest, compared in constant time — the
 * deploy token's primitives, which are the platform's one way of keeping a credential it hands out.
 */
object MachineSecrets:

  /** A secret and its digest: the secret for the person registering, the digest for the journal. */
  final case class Minted(secret: String, digest: String)

  def mint(): Minted =
    val secret = DeployTokens.mint().secret
    Minted(secret, DeployTokens.digest(secret))

  def digest(secret: String): String = DeployTokens.digest(secret)

  def matches(storedDigest: String, secret: String): Boolean =
    storedDigest.nonEmpty && DeployTokens.matches(storedDigest, secret)

  /** `machine:<organization>/<name>` read back into its two parts, when it is one. */
  def parseClientId(clientId: String): Option[(String, String)] =
    clientId.stripPrefix("machine:") match
      case rest if rest != clientId && rest.count(_ == '/') == 1 =>
        val (organization, name) = rest.span(_ != '/')
        Option.when(organization.nonEmpty && name.length > 1)((organization, name.drop(1)))
      case _ => None
