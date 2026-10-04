package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, PlatformVariables}

import java.nio.charset.StandardCharsets
import java.security.{GeneralSecurityException, SecureRandom}
import java.util.Base64
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.crypto.{AEADBadTagException, Cipher}

/**
 * A service's secret key: 32 bytes, written as their standard base64. `toString` never shows them.
 */
final class SecretKey private (private[runtime] val bytes: Array[Byte]):
  override def toString: String = "SecretKey(<32 bytes>)"

object SecretKey:
  val Length: Int = 32

  /** The form a key is written in, for a message that must say what was expected. */
  val Form: String =
    s"the standard base64 of exactly $Length bytes (`openssl rand -base64 $Length`)"

  /** Reads a key; the `Left` names the variable and the form it expects, never the text it read. */
  def parse(text: String): Either[String, SecretKey] =
    val decoded =
      try Some(Base64.getDecoder.decode(text.trim))
      catch case _: IllegalArgumentException => None
    decoded match
      case Some(bytes) if bytes.length == Length => Right(SecretKey(bytes))
      case Some(bytes) =>
        Left(s"${PlatformVariables.SecretKey} must be $Form; it decodes to ${bytes.length} bytes")
      case None => Left(s"${PlatformVariables.SecretKey} must be $Form; it is not base64")

  /** A fresh random key, for a test kit. */
  def generate(): SecretKey =
    val bytes = new Array[Byte](Length)
    SecretCipher.random.nextBytes(bytes)
    SecretKey(bytes)

  extension (key: SecretKey) def encoded: String = Base64.getEncoder.encodeToString(key.bytes)

/**
 * The stored form of a service secret: `0x01 ‖ nonce (12) ‖ AES-256-GCM(value) ‖ tag (16)`, with
 * the secret's name as associated data.
 *
 * Authenticated, so a key that is not the one a value was kept with is a detected failure and never
 * garbage. The name is bound, so a row copied under another name does not decrypt. The leading byte
 * is the form's version: one byte now, so that a later rotation of keys can tell forms apart.
 */
object SecretCipher:
  val Version: Byte    = 0x01
  val NonceLength: Int = 12
  val TagBits: Int     = 128

  private[runtime] val random = new SecureRandom()

  def encrypt(key: SecretKey, name: String, value: String): Array[Byte] =
    val nonce = new Array[Byte](NonceLength)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      SecretKeySpec(key.bytes, "AES"),
      GCMParameterSpec(TagBits, nonce)
    )
    cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8))
    val sealed_ = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8))
    Array(Version) ++ nonce ++ sealed_

  def decrypt(key: SecretKey, name: String, stored: Array[Byte]): String =
    if stored.isEmpty || stored(0) != Version then
      val seen = stored.headOption.fold("nothing")(b => f"version 0x$b%02x")
      throw CommandError(
        s"the secret '$name' is stored in a form this runtime cannot read ($seen)",
        ErrorCode.Internal
      )
    if stored.length < 1 + NonceLength + TagBits / 8 then
      throw CommandError(s"the secret '$name' is stored truncated", ErrorCode.Internal)
    val nonce  = stored.slice(1, 1 + NonceLength)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.DECRYPT_MODE,
      SecretKeySpec(key.bytes, "AES"),
      GCMParameterSpec(TagBits, nonce)
    )
    cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8))
    try
      String(
        cipher.doFinal(stored, 1 + NonceLength, stored.length - 1 - NonceLength),
        StandardCharsets.UTF_8
      )
    catch
      case _: AEADBadTagException =>
        throw CommandError(
          s"the secret key is not the one '$name' was kept with " +
            s"(${PlatformVariables.SecretKey} has changed, or the row was altered)",
          ErrorCode.Internal
        )
      case e: GeneralSecurityException =>
        throw CommandError(
          s"the secret '$name' could not be decrypted: ${e.getMessage}",
          ErrorCode.Internal
        )
