package com.thinkmorestupidless.ankka.controlplane.auth

import com.thinkmorestupidless.ankka.http.OpaqueCredentials

/**
 * The deploy token credential: how one is minted, recognised and checked.
 *
 * `ankka_<id>_<secret>` — a prefix, 16 hex characters of id, and 64 hex characters of secret. Three
 * properties are deliberate:
 *
 *   - **The prefix classifies it on sight.** The OIDC rule refuses anything that is not
 *     dot-dot-shaped before it parses, so the control plane must be able to tell which kind of
 *     credential it is holding without guessing. A secret scanner can recognise it too.
 *   - **Hex, so nothing quotes it.** No `.`, `+`, `/`, `=` or leading `-`: it survives a shell, a
 *     YAML file and a `curl -d` unquoted, which is where a CI credential actually lives.
 *   - **Only the digest is stored.** 256 bits from `SecureRandom` is far past the reach of a
 *     dictionary or a rainbow table, so a plain SHA-256 is enough and a key-derivation function
 *     would only add a tunable cost to a check that must not perform work at all. This is not a
 *     password table.
 *
 * The shape is `OpaqueCredentials`', which a service's client keys share (feature 054).
 */
object DeployTokens:

  val Prefix = "ankka_"

  private val credentials = OpaqueCredentials(Prefix, separator = "_")

  /** A freshly minted token: what to store, and the one string the creator is ever shown. */
  final case class Minted(id: String, secret: String, presented: String, digest: String)

  def mint(): Minted =
    val minted = credentials.mint()
    Minted(minted.id, minted.secret, minted.presented, minted.digest)

  /** SHA-256 of the secret half, lowercase hex. The only form that is ever written down. */
  def digest(secret: String): String = credentials.digest(secret)

  /** Whether a presented string is a deploy token at all, and its two halves if it is. */
  def parse(presented: String): Option[(String, String)] = credentials.parse(presented)

  /** Whether a presented string even claims to be a deploy token. */
  def looksLikeOne(presented: String): Boolean = credentials.looksLikeOne(presented)

  /**
   * Constant-time comparison of a presented secret against a stored digest.
   *
   * `MessageDigest.isEqual` rather than `==`: a digest comparison that returns early leaks how much
   * of a guess was right, one byte at a time.
   */
  def matches(storedDigest: String, secret: String): Boolean =
    credentials.matches(storedDigest, secret)
