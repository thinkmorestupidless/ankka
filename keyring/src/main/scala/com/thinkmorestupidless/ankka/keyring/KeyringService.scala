package com.thinkmorestupidless.ankka.keyring

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import com.thinkmorestupidless.ankka.core.{DataSubject, PersonalCipher}
import com.thinkmorestupidless.ankka.core.keyring.*

import java.time.Clock
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
 * What the keyring answers, whatever carries the asking (feature 042,
 * `contracts/keyring-and-protocol.md`): each answer is a status and a body, every request admitted
 * first, and nothing answered until the erasure log has been replayed.
 */
final class KeyringService(
    store: KeyStore,
    wrapping: Wrapping,
    admission: Admission,
    val notices: Notices,
    replay: ErasureLogReplay,
    clock: Clock = Clock.systemUTC()
)(using ExecutionContext):

  import KeyringService.*

  private val b64 = Base64.getEncoder

  private def admitted[A](who: Admission.Who, request: Admission.Request)(
      answer: => Future[Answer[A]]
  ): Future[Answer[A]] =
    if !replay.done then
      Future.successful(Answer.Unavailable("the erasure log is still being replayed"))
    else
      admission.admit(who, request).flatMap {
        case Left(why) => Future.successful(Answer.Refused(why))
        case Right(()) => answer
      }

  /** `POST /v1/keys`. */
  def key(who: Admission.Who, request: KeyRequest): Future[Answer[KeyReply]] =
    DataSubject.from(request.subject) match
      case Left(problem) => Future.successful(Answer.Invalid(problem))
      case Right(_)      =>
        admitted(who, Admission.Request.Key(request.project, request.subject, request.create)) {
          wrapping.subjectKey(request.project, request.subject, request.create).map {
            case Wrapping.Answer.Key(k) =>
              Answer.Ok(
                KeyReply(key = Some(b64.encodeToString(IArray.genericWrapArray(k).toArray)))
              )
            case Wrapping.Answer.Destroyed => Answer.Ok(KeyReply(destroyed = Some(true)))
            case Wrapping.Answer.Absent    => Answer.Missing(s"no key of ${request.subject}")
          }
        }

  /** `POST /v1/lookup-keys`. */
  def lookupKey(who: Admission.Who, request: LookupKeyRequest): Future[Answer[LookupKeyReply]] =
    admitted(who, Admission.Request.LookupKey(request.project)) {
      wrapping
        .lookupKey(request.project)
        .map(k => Answer.Ok(LookupKeyReply(b64.encodeToString(IArray.genericWrapArray(k).toArray))))
    }

  /** Whether `who` may hold the notices of `hello`'s project, as the service it says it is. */
  def mayListen(who: Admission.Who, hello: NoticeFrame.Hello): Future[Either[String, Unit]] =
    if !replay.done then Future.successful(Left("the erasure log is still being replayed"))
    else
      who match
        case Admission.Who.Service(com.thinkmorestupidless.ankka.http.Caller.Service(_, s))
            if s != hello.service =>
          Future.successful(Left(s"a connection of $s said hello as ${hello.service}"))
        case _ => admission.admit(who, Admission.Request.Notices(hello.project))

  /** `POST /v1/erasures`: destroys the key, then notifies every instance of the project. */
  def destroy(who: Admission.Who, order: ErasureOrder): Future[Answer[ErasureDestroyed]] =
    admitted(who, Admission.Request.Destroy(order.project, order.subject)) {
      for
        at <- store.destroy(order.seq, order.project, order.subject, clock.instant())
        _  <- notices.publish(order.seq, order.project, order.subject, rerun = false)
      yield Answer.Ok(ErasureDestroyed(at))
    }

  /** `POST /v1/erasures/{seq}/resend`: the notice again, to the instances connected now. */
  def resend(who: Admission.Who, seq: Long): Future[Answer[Unit]] =
    admitted(who, Admission.Request.Resend(seq)) {
      store.applied(seq).flatMap {
        case None => Future.successful(Answer.Missing(s"erasure $seq was not applied"))
        case Some((project, subject)) =>
          notices.publish(seq, project, subject, rerun = true).map(_ => Answer.Accepted)
      }
    }

  /** `GET /v1/erasures/{seq}`. */
  def status(who: Admission.Who, seq: Long): Future[Answer[ErasureStatus]] =
    admitted(who, Admission.Request.Status(seq))(notices.status(seq).map(Answer.Ok(_)))

  /**
   * `POST /v1/decrypt`: each field in the clear, or `null` where it holds no value; every field
   * must be of the request's project. A request naming an erased data subject is refused, and
   * recorded as a refusal; otherwise each data subject read is recorded with how many of its
   * fields, never a value (FR-031).
   */
  def decrypt(who: Admission.Who, request: DecryptRequest): Future[Answer[DecryptReply]] =
    admitted(who, Admission.Request.Decrypt(request.project, request.grant)) {
      val read = request.fields.zipWithIndex.map((f, i) =>
        Try(readFromArray[Envelope](f.bytes)).toOption
          .filter(e => e.`$personal` == 1 && DataSubject.from(e.subject).isRight)
          .toRight(s"field $i is not a personal envelope")
          .flatMap(e =>
            if e.project != request.project then
              Left(s"field $i is of project ${e.project}, not ${request.project}")
            else Right(e)
          )
      )
      read.collectFirst { case Left(why) => why } match
        case Some(why) => Future.successful(Answer.Invalid(why))
        case None      =>
          val envelopes = read.collect { case Right(e) => e }
          val subjects  = envelopes.map(_.subject).distinct
          Future
            .traverse(subjects)(s =>
              wrapping.subjectKey(request.project, s, create = false).map(s -> _)
            )
            .map(_.toMap)
            .flatMap { keys =>
              // An erased data subject is refused, as a revoked grant is (FR-031), and recorded.
              subjects.find(s => keys(s) == Wrapping.Answer.Destroyed) match
                case Some(erased) =>
                  val why = s"the data subject $erased is erased"
                  store
                    .refused(who.name, request.project, erased, why)
                    .map(_ => Answer.Refused(why))
                case None => opened(who, request, envelopes, keys)
            }
    }

  /** Each field opened under its subject's key, or `null` when it holds no value; recorded. */
  private def opened(
      who: Admission.Who,
      request: DecryptRequest,
      envelopes: Vector[Envelope],
      keys: Map[String, Wrapping.Answer]
  ): Future[Answer[DecryptReply]] =
    val values = envelopes.zipWithIndex.map { (e, i) =>
      (keys(e.subject), e.c) match
        case (Wrapping.Answer.Key(k), Some(c)) =>
          val ad = PersonalCipher.associatedData(e.subject, e.project, request.manifest)
          Try(Base64.getDecoder.decode(c)).toOption
            .flatMap(PersonalCipher.open(k, _, ad))
            .map(p => Right(Some(RawJson(p))))
            .getOrElse(Left(s"the personal envelope of field $i failed authentication"))
        case _ => Right(None)
    }
    values.collectFirst { case Left(why) => why } match
      case Some(why) => Future.successful(Answer.Invalid(why))
      case None      =>
        Future
          .traverse(envelopes.groupBy(_.subject).toVector)((s, es) =>
            store.decrypted(who.name, request.grant, request.project, s, es.size)
          )
          .map(_ => Answer.Ok(DecryptReply(values.collect { case Right(v) => v })))

object KeyringService:

  /** An answer, as a status the transport maps and a body when there is one. */
  enum Answer[+A]:
    case Ok(value: A)
    case Accepted
    case Refused(why: String)
    case Invalid(why: String)
    case Missing(why: String)
    case Unavailable(why: String)

  private[keyring] final case class Envelope(
      `$personal`: Int = 0,
      project: String = "",
      subject: String = "",
      c: Option[String] = None
  )
  private[keyring] given JsonValueCodec[Envelope] =
    JsonCodecMaker.make(CodecMakerConfig.withTransientDefault(false))
