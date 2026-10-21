package com.thinkmorestupidless.ankka.http

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}

/**
 * An opaque credential a machine holds: a prefix, 16 hex characters of id, an optional separator,
 * and 64 hex characters of secret. The control plane's deploy tokens are `ankka_<id>_<secret>`, a
 * service's client keys (feature 054) `ankka_ck_<id><secret>`; both are minted, recognised and
 * checked here, so the two cannot drift apart.
 *
 *   - **The prefix classifies it on sight**, before anything is parsed, and a secret scanner can
 *     recognise it too.
 *   - **Hex, so nothing quotes it.** It survives a shell, a YAML file and a header unquoted.
 *   - **Only the digest is stored.** 256 bits from `SecureRandom` is past the reach of any
 *     dictionary, so a plain SHA-256 is enough; a key-derivation function would only add a cost to
 *     a check that must perform no work at all.
 */
final class OpaqueCredentials(val prefix: String, separator: String = ""):
  import OpaqueCredentials.*

  private val Shape =
    s"${java.util.regex.Pattern.quote(prefix)}[0-9a-f]{$IdLength}${java.util.regex.Pattern
        .quote(separator)}[0-9a-f]{$SecretLength}".r

  /** A freshly minted credential: what to store, and the one string its holder is ever shown. */
  def mint(): Minted =
    val id     = hex(IdLength / 2)
    val secret = hex(SecretLength / 2)
    Minted(id, secret, s"$prefix$id$separator$secret", digest(secret))

  /** SHA-256 of the secret half, lower-case hex. The only form that is ever written down. */
  def digest(secret: String): String = OpaqueCredentials.digest(secret)

  /** Whether a presented string is one of these at all, and its id and secret if it is. */
  def parse(presented: String): Option[(String, String)] =
    Option.when(Shape.matches(presented)) {
      val body = presented.drop(prefix.length)
      (body.take(IdLength), body.drop(IdLength + separator.length))
    }

  /** Whether a presented string even claims to be one: by its prefix alone. */
  def looksLikeOne(presented: String): Boolean = presented.startsWith(prefix)

  /**
   * Constant-time comparison of a presented secret against a stored digest: a comparison that
   * returns early leaks how much of a guess was right, one byte at a time.
   */
  def matches(storedDigest: String, secret: String): Boolean =
    MessageDigest.isEqual(storedDigest.getBytes(UTF_8), digest(secret).getBytes(UTF_8))

object OpaqueCredentials:

  private val IdLength     = 16
  private val SecretLength = 64

  private val random = new SecureRandom()

  final case class Minted(id: String, secret: String, presented: String, digest: String)

  def digest(secret: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(secret.getBytes(UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  private def hex(bytes: Int): String =
    val buffer = new Array[Byte](bytes)
    random.nextBytes(buffer)
    buffer.map(b => f"${b & 0xff}%02x").mkString
