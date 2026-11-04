package com.thinkmorestupidless.ankka.core

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.{AEADBadTagException, Cipher}
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import scala.compiletime.summonFrom

/**
 * Who a personal field is about (feature 042, FR-002): 1 to 253 characters from letters, digits,
 * `.`, `_`, `-`, `/` and `:`. Every SDK holds the same rule to
 * `protocol/fixtures/personal/subjects.json`.
 */
opaque type DataSubject = String

object DataSubject:

  /** The rule, in the words every SDK refuses with. */
  val Rule: String =
    "a data subject is 1 to 253 characters from letters, digits, '.', '_', '-', '/' and ':'"

  private val Pattern = "^[A-Za-z0-9._/:-]{1,253}$".r

  /** `value` as a data subject, or why it cannot be one. */
  def from(value: String): Either[String, DataSubject] =
    if value != null && Pattern.matches(value) then Right(value)
    else Left(s"'${String.valueOf(value)}' is not a data subject: $Rule")

  /**
   * `value` as a data subject; throws `IllegalArgumentException` with the rule when it is not one.
   */
  def apply(value: String): DataSubject =
    from(value).fold(problem => throw IllegalArgumentException(problem), identity)

  extension (subject: DataSubject) def value: String = subject

/**
 * A personal field (feature 042): a value about one data subject, never held in the clear.
 *
 * Written by a `Serializer` it is the personal envelope, sealed under the subject key; read back it
 * is `Present`, or `Erased` once the subject key is destroyed. Where no serializer is writing (an
 * endpoint's reply or a caller's body) it is the value with its subject beside it. Its textual form
 * names the subject and never the value, so a log line holds no personal data.
 */
enum Personal[+A]:
  case Present(subject: DataSubject, value: A)
  case Erased(subject: DataSubject)

  def subject: DataSubject

  /** The value, or `default` once erased. */
  def orElse[B >: A](default: => B): B = this match
    case Present(_, value) => value
    case Erased(_)         => default

  /** Whether the data subject was erased. */
  def isErased: Boolean = this match
    case Erased(_) => true
    case _         => false

  override def toString: String = s"Personal(${subject.value})"

object Personal:

  def apply[A](subject: DataSubject, value: A): Personal[A] = Present(subject, value)

  /** The `details` key of a `CommandError` a personal field fails with, and its values. */
  val ErrorKey: String      = "personal"
  val Unavailable: String   = "unavailable"
  val ErasedRefusal: String = "erased"
  val Corrupt: String       = "corrupt"

  /** The envelope's version; a reader refuses one it does not know. */
  val Version: Int = 1

  private val EnvelopeKey = "\"$personal\""

  /**
   * Whether `text` holds a personal envelope, anywhere in it: what telemetry drops rather than
   * export (R24). Deliberately loose: a false positive costs an attribute, a false negative a leak.
   */
  def holdsEnvelope(text: String): Boolean = text != null && text.contains(EnvelopeKey)

  /**
   * The codec of a personal field of `A`: `A`'s own codec where one is in scope, else one derived
   * with `JsonCodecMaker` (for `String`, `Int` and the rest, which jsoniter gives no `given` for).
   */
  inline given codec[A]: JsonValueCodec[Personal[A]] =
    summonFrom {
      case inner: JsonValueCodec[A] => PersonalCodec[A](inner)
      case _                        => PersonalCodec[A](JsonCodecMaker.make[A])
    }

/**
 * The manifest a `Serializer` is encoding or decoding under, for the duration of one call (feature
 * 042, R2): the personal codec binds it into the envelope's associated data. No scope is the edge,
 * where the codec writes the edge form instead.
 */
object PersonalScope:

  final case class Scope(manifest: String, nonce: Option[Array[Byte]])

  private val bound = new ThreadLocal[Scope]

  /** The scope bound on this thread, if any. */
  def current: Option[Scope] = Option(bound.get)

  /**
   * Runs `body` under `manifest`. `nonce` fixes the nonce every envelope written in `body` is
   * sealed with, which only the fixtures' tests may do: a nonce used twice under one key breaks
   * GCM.
   */
  def within[T](manifest: String, nonce: Option[Array[Byte]] = None)(body: => T): T =
    val before = bound.get
    bound.set(Scope(manifest, nonce))
    try body
    finally if before == null then bound.remove() else bound.set(before)

  /** Runs `body` with no scope bound, as at the edge, restoring what was bound before. */
  def outside[T](body: => T): T =
    val before = bound.get
    bound.remove()
    try body
    finally if before != null then bound.set(before)

/** AES-256-GCM over a field's own JSON, with the associated data of R11 (feature 042). */
object PersonalCipher:

  private val NonceBytes = 12
  private val TagBits    = 128
  private val random     = new SecureRandom()

  /** Each part as a 4-byte big-endian length and its UTF-8 bytes: subject, project, manifest. */
  def associatedData(subject: String, project: String, manifest: String): Array[Byte] =
    val parts = Vector(subject, project, manifest).map(_.getBytes(UTF_8))
    val out   = ByteBuffer.allocate(parts.map(_.length + 4).sum)
    parts.foreach(p => out.putInt(p.length).put(p))
    out.array()

  /** `nonce ‖ ciphertext ‖ tag`. */
  def seal(
      key: IArray[Byte],
      nonce: Option[Array[Byte]],
      plaintext: Array[Byte],
      ad: Array[Byte]
  ): Array[Byte] =
    val iv = nonce.getOrElse {
      val n = new Array[Byte](NonceBytes)
      random.nextBytes(n)
      n
    }
    require(iv.length == NonceBytes, s"a nonce is $NonceBytes bytes, was ${iv.length}")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
      Cipher.ENCRYPT_MODE,
      SecretKeySpec(IArray.genericWrapArray(key).toArray, "AES"),
      GCMParameterSpec(TagBits, iv)
    )
    cipher.updateAAD(ad)
    iv ++ cipher.doFinal(plaintext)

  /** The plaintext, or `None` when the tag does not verify under this key and associated data. */
  def open(key: IArray[Byte], boxed: Array[Byte], ad: Array[Byte]): Option[Array[Byte]] =
    if boxed.length < NonceBytes + TagBits / 8 then None
    else
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(IArray.genericWrapArray(key).toArray, "AES"),
        GCMParameterSpec(TagBits, boxed, 0, NonceBytes)
      )
      cipher.updateAAD(ad)
      try Some(cipher.doFinal(boxed, NonceBytes, boxed.length - NonceBytes))
      catch case _: AEADBadTagException => None

/**
 * Writes and reads a personal field: the envelope under a `PersonalScope`, the edge form outside
 * one. Not derived, because what it writes depends on the scope and the keyring, not on `A` alone.
 */
final class PersonalCodec[A](inner: JsonValueCodec[A]) extends JsonValueCodec[Personal[A]]:

  import Personal.{Erased, Present}

  def nullValue: Personal[A] = null

  def encodeValue(x: Personal[A], out: JsonWriter): Unit =
    PersonalScope.current match
      case None        => writeEdge(x, out)
      case Some(scope) => writeEnvelope(x, scope, out)

  def decodeValue(in: JsonReader, default: Personal[A]): Personal[A] =
    PersonalScope.current match
      case None        => readEdge(in)
      case Some(scope) => readEnvelope(in, scope)

  // ── the edge form: {"subject": s, "value": v} or {"subject": s} ──────────────

  private def writeEdge(x: Personal[A], out: JsonWriter): Unit =
    out.writeObjectStart()
    out.writeKey("subject")
    out.writeVal(x.subject.value)
    x match
      case Present(_, value) =>
        out.writeKey("value")
        inner.encodeValue(value, out)
      case Erased(_) => ()
    out.writeObjectEnd()

  private def readEdge(in: JsonReader): Personal[A] =
    if !in.isNextToken('{') then
      in.decodeError("expected a personal field: {\"subject\", \"value\"}")
    var subject: String = null
    var value: A        = inner.nullValue
    var hasValue        = false
    if !in.isNextToken('}') then
      in.rollbackToken()
      while
        in.readKeyAsString() match
          case "subject" => subject = in.readString(null)
          case "value"   =>
            value = inner.decodeValue(in, inner.nullValue)
            hasValue = true
          case _ => in.skip()
        in.isNextToken(',')
      do ()
      if !in.isCurrentToken('}') then in.objectEndOrCommaError()
    if subject == null then in.decodeError("a personal field names its data subject")
    val s = DataSubject.from(subject).fold(p => in.decodeError(p), identity)
    if hasValue then Present(s, value) else Erased(s)

  // ── the envelope: {"$personal":1,"project":p,"subject":s,"c":b64} ────────────

  private def writeEnvelope(x: Personal[A], scope: PersonalScope.Scope, out: JsonWriter): Unit =
    val keyring = Keyring.current
    val project = keyring.project
    val boxed   = x match
      case Erased(_)            => None
      case Present(subj, value) =>
        PersonalCodec.ask(keyring.key(project, subj, create = true)) match
          case KeyAnswer.Absent =>
            throw CommandError(
              s"the keyring made no key for '${subj.value}' when asked to; nothing was written",
              ErrorCode.Unavailable,
              Map(Personal.ErrorKey -> Personal.Unavailable)
            )
          case KeyAnswer.Destroyed =>
            throw CommandError(
              s"the data subject '${subj.value}' is erased; nothing was written",
              ErrorCode.Conflict,
              Map(Personal.ErrorKey -> Personal.ErasedRefusal)
            )
          case KeyAnswer.Key(key) =>
            val plaintext = writeToArrayReentrant(value)(using inner)
            val ad        = PersonalCipher.associatedData(subj.value, project, scope.manifest)
            Some(PersonalCipher.seal(key, scope.nonce, plaintext, ad))
    out.writeObjectStart()
    out.writeKey("$personal")
    out.writeVal(Personal.Version)
    out.writeKey("project")
    out.writeVal(project)
    out.writeKey("subject")
    out.writeVal(x.subject.value)
    boxed.foreach { bytes =>
      out.writeKey("c")
      out.writeVal(Base64.getEncoder.encodeToString(bytes))
    }
    out.writeObjectEnd()

  private def readEnvelope(in: JsonReader, scope: PersonalScope.Scope): Personal[A] =
    if !in.isNextToken('{') then in.decodeError("expected a personal envelope")
    var version         = -1
    var project: String = null
    var subject: String = null
    var boxed: String   = null
    if !in.isNextToken('}') then
      in.rollbackToken()
      while
        in.readKeyAsString() match
          case "$personal" => version = in.readInt()
          case "project"   => project = in.readString(null)
          case "subject"   => subject = in.readString(null)
          case "c"         => boxed = in.readString(null)
          case _           => in.skip()
        in.isNextToken(',')
      do ()
      if !in.isCurrentToken('}') then in.objectEndOrCommaError()
    if subject == null || project == null then
      in.decodeError("a personal envelope names its project and its data subject")
    val s = DataSubject.from(subject).fold(p => in.decodeError(p), identity)
    if version != Personal.Version then
      throw corrupt(subject, s"its version is $version, and this reader knows ${Personal.Version}")
    if boxed == null then Erased(s)
    else
      PersonalCodec.ask(Keyring.current.key(project, s, create = false)) match
        case KeyAnswer.Destroyed | KeyAnswer.Absent => Erased(s)
        case KeyAnswer.Key(key)                     =>
          val bytes =
            try Base64.getDecoder.decode(boxed)
            catch case _: IllegalArgumentException => throw corrupt(subject, "it is not base64")
          val ad = PersonalCipher.associatedData(subject, project, scope.manifest)
          PersonalCipher.open(key, bytes, ad) match
            case None            => throw corrupt(subject, "it failed authentication")
            case Some(plaintext) => Present(s, readFromArrayReentrant(plaintext)(using inner))

  private def corrupt(subject: String, why: String): CommandError =
    CommandError(
      s"the personal envelope of '$subject' is corrupt: $why",
      ErrorCode.Internal,
      Map(Personal.ErrorKey -> Personal.Corrupt)
    )

object PersonalCodec:

  /** `answer`, with the keyring's unavailability rethrown as `KeyringUnavailable`. */
  private[core] inline def ask(answer: => KeyAnswer): KeyAnswer =
    try answer
    catch case e: CommandError if e.code == ErrorCode.Unavailable => throw KeyringUnavailable(e)
