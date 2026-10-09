package com.thinkmorestupidless.ankka.core.personal

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.{Cipher, Mac}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

/**
 * What a personal field's codec asks for a key. The runtime gives a service one that speaks to the
 * keyring over the service's channel and caches what it is told; the test kit gives one in memory.
 * Every call may block: a codec runs on the thread that serializes, which in a service is a virtual
 * thread or a projection's, and a miss is one round trip.
 */
trait KeyringHandle:
  /**
   * The key one data subject's personal fields are encrypted under in one project. `create` is true
   * only when a present value is being written in the caller's own project: that is the one moment
   * a key may be made. A key the keyring has destroyed, or never had, is `Destroyed`; a project the
   * caller may not read is `Refused`. A keyring that cannot be reached throws `Unavailable`.
   */
  def key(project: String, subject: String, create: Boolean): KeyResult

  /** The key a project's lookup tokens are made with. Not a subject key; the keyring keeps it. */
  def lookupKey(project: String): Array[Byte]

enum KeyResult:
  case Available(key: Array[Byte])
  case Destroyed(erasureId: String)
  case Refused(reason: String)

object KeyringHandle:

  /**
   * Where no service runs: every call refused as `Unavailable`, as `SecretStore.unavailable` is.
   */
  val unavailable: KeyringHandle = new KeyringHandle:
    def key(project: String, subject: String, create: Boolean): KeyResult =
      throw PersonalScope.unavailable()
    def lookupKey(project: String): Array[Byte] = throw PersonalScope.unavailable()
    override def toString: String               = "KeyringHandle.unavailable"

/**
 * The keyring and the project a personal field is written and read under, on the thread doing it.
 *
 * Not a global: one JVM may run several services of several projects (every multi-service suite
 * does), and only the code serializing on a thread knows which service it is serializing for. The
 * runtime sets a scope at every place it serializes a domain value — an entity's adapters, a view's
 * rows, a topic's messages, a call's command and reply, an HTTP body — and the codec fails closed
 * where nothing set one: a present value is never written, and an envelope never decrypted, under a
 * keyring nobody chose.
 *
 * `lookupAllowed` is true only around a view's row writes, so a lookup token reaches a row and
 * never a journal or a topic.
 */
final case class PersonalScope(keyring: KeyringHandle, project: String, lookupAllowed: Boolean)

object PersonalScope:

  private val local = ThreadLocal[PersonalScope]()

  def current: Option[PersonalScope] = Option(local.get())

  def within[T](keyring: KeyringHandle, project: String, lookupAllowed: Boolean = false)(
      body: => T
  ): T =
    val previous = local.get()
    local.set(PersonalScope(keyring, project, lookupAllowed))
    try body
    finally if previous == null then local.remove() else local.set(previous)

  /**
   * The same scope with lookup tokens allowed, for a view's row write. No scope, nothing changes.
   */
  def allowingLookup[T](body: => T): T =
    current match
      case Some(scope) => within(scope.keyring, scope.project, lookupAllowed = true)(body)
      case None        => body

  private[ankka] def unavailable(): CommandError =
    CommandError(
      "no keyring is available here: a personal field is written and read only inside a service, " +
        "or inside a test kit",
      ErrorCode.Unavailable
    )

/**
 * The one cipher of a personal envelope: AES-256-GCM, a version byte, a 12-byte nonce, a 16-byte
 * tag.
 */
private[ankka] object PersonalCipher:
  val Version: Byte       = 0x01
  private val NonceLength = 12
  private val TagBits     = 128
  private val random      = SecureRandom()

  def newKey(): Array[Byte] =
    val key = new Array[Byte](32)
    random.nextBytes(key)
    key

  /**
   * Associated data: the subject and its project, so an envelope moved to another fails to open.
   */
  def associated(subject: String, project: String): Array[Byte] =
    s"$subject\u0000$project".getBytes(java.nio.charset.StandardCharsets.UTF_8)

  def encrypt(key: Array[Byte], aad: Array[Byte], plaintext: Array[Byte]): Array[Byte] =
    val nonce = new Array[Byte](NonceLength)
    random.nextBytes(nonce)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TagBits, nonce))
    cipher.updateAAD(aad)
    val ciphertext = cipher.doFinal(plaintext)
    val out        = new Array[Byte](1 + NonceLength + ciphertext.length)
    out(0) = Version
    System.arraycopy(nonce, 0, out, 1, NonceLength)
    System.arraycopy(ciphertext, 0, out, 1 + NonceLength, ciphertext.length)
    out

  /**
   * The plaintext, or `None` when the bytes do not open under this key and this associated data.
   */
  def decrypt(key: Array[Byte], aad: Array[Byte], stored: Array[Byte]): Option[Array[Byte]] =
    if stored.length < 1 + NonceLength + TagBits / 8 || stored(0) != Version then None
    else
      try
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
          Cipher.DECRYPT_MODE,
          SecretKeySpec(key, "AES"),
          GCMParameterSpec(TagBits, stored, 1, NonceLength)
        )
        cipher.updateAAD(aad)
        Some(cipher.doFinal(stored, 1 + NonceLength, stored.length - 1 - NonceLength))
      catch case _: javax.crypto.AEADBadTagException => None

/** A keyed hash of a personal field's value, so a declared query can match it by equality. */
object LookupTokens:
  def token(lookupKey: Array[Byte], plaintext: Array[Byte]): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(lookupKey, "HmacSHA256"))
    HexFormat.of().formatHex(mac.doFinal(plaintext))

  /**
   * The token of a text value, as a field's codec makes it for a `Personal[String]`: over the
   * value's JSON encoding. This is what a caller hands a declared query as the parameter for such a
   * field.
   */
  def forText(lookupKey: Array[Byte], value: String): String =
    token(
      lookupKey,
      com.github.plokhotnyuk.jsoniter_scala.core.writeToArrayReentrant(value)(using TextCodec)
    )

  private val TextCodec: com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec[String] =
    com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make[String]
