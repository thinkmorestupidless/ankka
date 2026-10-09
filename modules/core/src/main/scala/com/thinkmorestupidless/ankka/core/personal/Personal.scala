package com.thinkmorestupidless.ankka.core.personal

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.{Codecs, CommandError, ErrorCode}

import java.util.Base64

/**
 * A field that is about a person: present, with the data subject it belongs to and its value, or
 * erased, once that subject's key has been destroyed.
 *
 * Every store holds it only as a personal envelope — the subject and its project readable, the
 * value encrypted under the subject's key — because its codec writes nothing else; the runtime, the
 * sidecar and the journal see ordinary JSON and never look inside. A handler that reads one decides
 * what an erased value means; nothing throws on replay.
 *
 * `project` is the project whose key the value is under: `None` until it has been written, when the
 * writing service's own is used, and the envelope's once it has been read, so a value another
 * project published is written again under that project's key. `lookup` asks for a lookup token
 * where a view's row holds the value. Neither is part of equality.
 *
 * There is no `get`: the erased case must be handled.
 */
enum Personal[+A]:
  case Present(subject: String, value: A, project: Option[String] = None, lookup: Boolean = false)
  case Erased(subject: String, project: Option[String] = None)

  def subject: String

  def toOption: Option[A] = this match
    case Present(_, value, _, _) => Some(value)
    case Erased(_, _)            => None

  def isErased: Boolean = this match
    case Erased(_, _) => true
    case _            => false

  def getOrElse[B >: A](default: => B): B = toOption.getOrElse(default)

  def map[B](f: A => B): Personal[B] = this match
    case Present(s, value, p, l) => Present(s, f(value), p, l)
    case Erased(s, p)            => Erased(s, p)

  /** `Personal(<subject>)`, so an event interpolated into a log line prints no value. */
  override def toString: String = s"Personal($subject)"

  override def equals(other: Any): Boolean = (this, other) match
    case (Present(s1, v1, _, _), Present(s2, v2, _, _)) => s1 == s2 && v1 == v2
    case (Erased(s1, _), Erased(s2, _))                 => s1 == s2
    case _                                              => false

  override def hashCode: Int = this match
    case Present(s, v, _, _) => (s, v).##
    case Erased(s, _)        => s.## * 31

object Personal:

  /** A present value of `subject`, refusing a subject outside the data subject rule. */
  def present[A](subject: String, value: A): Personal[A] =
    DataSubject.require(subject)
    Present(subject, value)

  /**
   * A present value whose row in a view carries a lookup token, so a declared query can match it.
   */
  def lookup[A](subject: String, value: A): Personal[A] =
    DataSubject.require(subject)
    Present(subject, value, lookup = true)

  def erased(subject: String): Personal[Nothing] =
    DataSubject.require(subject)
    Erased(subject)

  /**
   * The envelope codec, found by a derived codec for any type with a `Personal` field: jsoniter
   * looks for a field type's codec in its implicit scope, and this companion is it. Inline, so the
   * inner value's codec is derived where `A` is known, with the platform's shared configuration.
   */
  inline given codec[A]: JsonValueCodec[Personal[A]] = PersonalCodec[A](Codecs.make[A])

/** The data subject rule: an opaque id the domain chooses, 1 to 253 of `A–Z a–z 0–9 . _ - / :`. */
object DataSubject:
  private val Rule = "[A-Za-z0-9._\\-/:]{1,253}".r

  def problems(subject: String): Vector[String] =
    if subject == null || subject.isEmpty then Vector("a data subject is required")
    else if subject.length > 253 then
      Vector(s"a data subject is at most 253 characters, not ${subject.length}")
    else if !Rule.matches(subject) then
      Vector(s"a data subject is letters, digits, '.', '_', '-', '/' and ':' only: '$subject'")
    else Vector.empty

  def require(subject: String): Unit =
    problems(subject).headOption.foreach(p => throw IllegalArgumentException(p))

/**
 * Writes and reads the personal envelope (`contracts/personal-envelope.md`):
 * `{"subject":…,"project":…,"data":…[,"lookup":…]}` when present, `{"subject":…,"project":…}` when
 * erased. The plaintext is the value's JSON; the associated data the subject and project.
 */
final class PersonalCodec[A](inner: JsonValueCodec[A]) extends JsonValueCodec[Personal[A]]:

  def nullValue: Personal[A] = null

  def encodeValue(x: Personal[A], out: JsonWriter): Unit = x match
    case Personal.Present(subject, value, valueProject, lookup) =>
      val scope   = PersonalScope.current.getOrElse(throw PersonalScope.unavailable())
      val project = valueProject.getOrElse(scope.project)
      val key = scope.keyring.key(project, subject, create = project == scope.project) match
        case KeyResult.Available(k) => k
        case KeyResult.Destroyed(_) =>
          throw CommandError(
            s"data subject $subject is erased in project $project: no personal field can be written for it",
            ErrorCode.BadRequest
          )
        case KeyResult.Refused(reason) =>
          throw CommandError(
            s"the keyring refused data subject $subject of project $project: $reason",
            ErrorCode.Forbidden
          )
      val plaintext = writeToArrayReentrant(value)(using inner)
      val data = PersonalCipher.encrypt(key, PersonalCipher.associated(subject, project), plaintext)
      out.writeObjectStart()
      out.writeKey("subject")
      out.writeVal(subject)
      out.writeKey("project")
      out.writeVal(project)
      out.writeKey("data")
      out.writeVal(Base64.getEncoder.encodeToString(data))
      if lookup && scope.lookupAllowed then
        out.writeKey("lookup")
        out.writeVal(LookupTokens.token(scope.keyring.lookupKey(project), plaintext))
      out.writeObjectEnd()
    case Personal.Erased(subject, valueProject) =>
      val project = valueProject
        .orElse(PersonalScope.current.map(_.project))
        .getOrElse(throw PersonalScope.unavailable())
      out.writeObjectStart()
      out.writeKey("subject")
      out.writeVal(subject)
      out.writeKey("project")
      out.writeVal(project)
      out.writeObjectEnd()

  def decodeValue(in: JsonReader, default: Personal[A]): Personal[A] =
    if !in.isNextToken('{') then in.decodeError("expected a personal envelope")
    var subject: String = null
    var project: String = null
    var data: String    = null
    var lookup: String  = null
    if !in.isNextToken('}') then
      in.rollbackToken()
      while
        in.readKeyAsString() match
          case "subject" => subject = in.readString(null)
          case "project" => project = in.readString(null)
          case "data"    => data = in.readString(null)
          case "lookup"  => lookup = in.readString(null)
          case other     => in.decodeError(s"unknown key '$other' in a personal envelope")
        in.isNextToken(',')
      do ()
      if !in.isCurrentToken('}') then in.objectEndOrCommaError()
    if subject == null || DataSubject.problems(subject).nonEmpty then
      in.decodeError("a personal envelope needs a valid subject")
    if project == null || project.isEmpty then
      in.decodeError("a personal envelope needs its project")
    if data == null then Personal.Erased(subject, Some(project))
    else
      val scope = PersonalScope.current.getOrElse(throw PersonalScope.unavailable())
      scope.keyring.key(project, subject, create = false) match
        case KeyResult.Available(key) =>
          val stored =
            try Base64.getDecoder.decode(data)
            catch
              case _: IllegalArgumentException =>
                in.decodeError("personal envelope corrupt: data is not base64")
          PersonalCipher.decrypt(key, PersonalCipher.associated(subject, project), stored) match
            case Some(plaintext) =>
              Personal.Present(
                subject,
                readFromArrayReentrant(plaintext)(using inner),
                Some(project),
                lookup != null
              )
            case None =>
              in.decodeError(s"personal envelope corrupt: it does not open as $subject of $project")
        case KeyResult.Destroyed(_) | KeyResult.Refused(_) =>
          Personal.Erased(subject, Some(project))
