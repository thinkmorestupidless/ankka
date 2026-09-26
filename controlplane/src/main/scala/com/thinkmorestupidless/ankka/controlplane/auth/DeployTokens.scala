package com.thinkmorestupidless.ankka.controlplane.auth

import java.security.{MessageDigest, SecureRandom}

/**
 * The deploy token credential: how one is minted, recognised and checked.
 *
 * `ankka_<id>_<secret>` — a prefix, 16 hex characters of id, and 64 hex characters of secret. Three
 * properties are deliberate:
 *
 *   - **The prefix classifies it on sight.** `TokenVerifier` refuses anything that is not
 *     dot-dot-shaped before it parses, so the control plane must be able to tell which kind of
 *     credential it is holding without guessing. A secret scanner can recognise it too.
 *   - **Hex, so nothing quotes it.** No `.`, `+`, `/`, `=` or leading `-`: it survives a shell, a
 *     YAML file and a `curl -d` unquoted, which is where a CI credential actually lives.
 *   - **Only the digest is stored.** 256 bits from `SecureRandom` is far past the reach of a
 *     dictionary or a rainbow table, so a plain SHA-256 is enough and a key-derivation function
 *     would only add a tunable cost to a check that must not perform work at all. This is not a
 *     password table.
 */
object DeployTokens:

  val Prefix = "ankka_"

  private val IdLength     = 16
  private val SecretLength = 64

  private val Shape = s"$Prefix[0-9a-f]{$IdLength}_[0-9a-f]{$SecretLength}".r

  private val random = new SecureRandom()

  /** A freshly minted token: what to store, and the one string the creator is ever shown. */
  final case class Minted(id: String, secret: String, presented: String, digest: String)

  def mint(): Minted =
    val id     = hex(IdLength / 2)
    val secret = hex(SecretLength / 2)
    Minted(id, secret, s"$Prefix${id}_$secret", digest(secret))

  /** SHA-256 of the secret half, lowercase hex. The only form that is ever written down. */
  def digest(secret: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** Whether a presented string is a deploy token at all, and its two halves if it is. */
  def parse(presented: String): Option[(String, String)] =
    Option.when(Shape.matches(presented)) {
      val body = presented.drop(Prefix.length)
      val at   = body.indexOf('_')
      (body.take(at), body.drop(at + 1))
    }

  /** Whether a presented string even claims to be a deploy token. */
  def looksLikeOne(presented: String): Boolean = presented.startsWith(Prefix)

  /**
   * Constant-time comparison of a presented secret against a stored digest.
   *
   * `MessageDigest.isEqual` rather than `==`: a digest comparison that returns early leaks how much
   * of a guess was right, one byte at a time.
   */
  def matches(storedDigest: String, secret: String): Boolean =
    MessageDigest.isEqual(
      storedDigest.getBytes(java.nio.charset.StandardCharsets.UTF_8),
      digest(secret).getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )

  private def hex(bytes: Int): String =
    val buffer = new Array[Byte](bytes)
    random.nextBytes(buffer)
    buffer.map(b => f"${b & 0xff}%02x").mkString
