package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.core.Codecs

import java.time.{Instant, LocalDate}

/**
 * `POST /projects/{id}/erasures` (feature 042): erase one data subject of the project — at once, or
 * held until `notBefore` for the reason given. `correlationId` joins this request to one in another
 * project for the same person; the platform reads nothing into it.
 */
final case class RequestErasure(
    subject: String,
    notBefore: Option[LocalDate] = None,
    reason: Option[String] = None,
    correlationId: Option[String] = None
)

/** `POST /projects/{id}/erasures/{e}/override`: an owner applying a held request now, and why. */
final case class OverrideHold(reason: String)

/** Where an erasure request stands. */
enum ErasureState:
  /** Waiting for its not-before date. */
  case Held

  /** Taken back while held; nothing was destroyed. */
  case Withdrawn

  /** Taken over by a later request from whoever asked for it. */
  case Replaced

  /** Being applied: the log written, the key destroyed, the services completing. */
  case Applying

  /** Every service of the project has completed. */
  case Applied

  /** Applied, and every deletion it made is final. */
  case Final

  /**
   * Final, and past the installation's reapplication grace: its handlers run again only on request.
   */
  case Settled

  /** A step failed; it is tried again on the next sweep. */
  case Failed

  /** Asked for by a service its grants do not allow: recorded, and nothing done. */
  case Refused

object ErasureState:
  def byName(name: String): Option[ErasureState] = values.find(_.toString.equalsIgnoreCase(name))

  /** One word on the wire, as a lifecycle is. */
  given codec: JsonValueCodec[ErasureState] = new JsonValueCodec[ErasureState]:
    def decodeValue(in: JsonReader, default: ErasureState): ErasureState =
      val name = in.readString(null)
      byName(name).getOrElse(in.decodeError(s"unknown erasure state '$name'"))
    def encodeValue(x: ErasureState, out: JsonWriter): Unit = out.writeVal(x.toString.toLowerCase)
    def nullValue: ErasureState                             = null

/** Who asked for, withdrew or overrode an erasure request: a member, or a service of a project. */
final case class ErasureWho(
    kind: String,
    subject: String,
    display: Option[String] = None,
    project: Option[String] = None
)

final case class ErasureOverride(by: ErasureWho, reason: String, at: Instant)

/** One service's completion of an erasure: what it did, and what its erasure handler reported. */
final case class ErasureServiceCompletion(
    service: String,
    completedAt: Instant,
    handler: Option[String] = None,
    objectsErased: Option[Long] = None,
    objectsFinalAt: Option[Instant] = None,
    /**
     * A service with no running instance when the key was destroyed: it applies at its next start.
     */
    byAbsence: Boolean = false
)

/**
 * An erasure request, as a member reads it. It names the data subject and holds nothing personal.
 */
final case class ErasureRequest(
    id: String,
    projectId: String,
    subject: String,
    state: ErasureState,
    askedBy: ErasureWho,
    askedAt: Instant,
    notBefore: Option[LocalDate] = None,
    reason: Option[String] = None,
    correlationId: Option[String] = None,
    overridden: Option[ErasureOverride] = None,
    withdrawnBy: Option[ErasureWho] = None,
    replacedBy: Option[String] = None,
    sequence: Option[Long] = None,
    keyDestroyedAt: Option[Instant] = None,
    completions: Vector[ErasureServiceCompletion] = Vector.empty,
    appliedAt: Option[Instant] = None,
    finalAt: Option[Instant] = None,
    failure: Option[String] = None
)

/**
 * What a member fetches for an applied erasure request, to give the data subject: the request, who
 * asked, each service's completion and when the erasure became final. Nothing personal: the subject
 * is the pseudonymous id the domain chose.
 */
final case class ErasureCertificate(request: ErasureRequest, issuedAt: Instant, statement: String)

/** What is wrong with an erasure request, checked identically by the CLI and the server. */
object ErasureRequests:
  private val Subject     = "[A-Za-z0-9._\\-/:]{1,253}".r
  val MaxReason: Int      = 500
  val MaxCorrelation: Int = 128

  def problems(request: RequestErasure, today: LocalDate): Vector[String] =
    Vector(
      Option.unless(Subject.matches(request.subject))(
        s"a data subject is 1 to 253 letters, digits, '.', '_', '-', '/' and ':': '${request.subject}'"
      ),
      request.notBefore
        .filter(_.isBefore(today))
        .map(d => s"the not-before date $d has passed; ask for an erasure without one"),
      Option.when(request.notBefore.isDefined && request.reason.forall(_.isBlank))(
        "a held erasure request needs a reason: the hold the law puts on the data"
      ),
      request.reason
        .filter(_.length > MaxReason)
        .map(_ => s"a reason is at most $MaxReason characters"),
      request.correlationId
        .filter(c => c.isEmpty || c.length > MaxCorrelation)
        .map(_ => s"a correlation id is 1 to $MaxCorrelation characters")
    ).flatten

/**
 * One thing that happened to a project, newest first, for `GET /projects/{id}/history` (FR-016): an
 * erasure asked for, withdrawn, applied or failed. `subject` is the pseudonymous id the domain
 * chose, never a personal field.
 */
final case class ProjectHistoryEntry(
    at: Instant,
    kind: String,
    erasureId: String,
    subject: String,
    by: Option[ErasureWho] = None,
    detail: Option[String] = None
)

object ProjectHistory:
  val Limit: Int = 50

  private val Steps = Vector(
    "erasure-requested",
    "erasure-overridden",
    "erasure-withdrawn",
    "erasure-failed",
    "erasure-applied",
    "erasure-refused"
  )

  /** A project's history, read from its erasure requests: the newest `Limit` entries. */
  def of(requests: Seq[ErasureRequest]): Vector[ProjectHistoryEntry] =
    requests
      .flatMap { r =>
        Vector(
          Some(
            ProjectHistoryEntry(r.askedAt, "erasure-requested", r.id, r.subject, Some(r.askedBy))
          ),
          r.overridden.map(o =>
            ProjectHistoryEntry(
              o.at,
              "erasure-overridden",
              r.id,
              r.subject,
              Some(o.by),
              Some(o.reason)
            )
          ),
          r.withdrawnBy.map(by =>
            ProjectHistoryEntry(r.askedAt, "erasure-withdrawn", r.id, r.subject, Some(by))
          ),
          r.appliedAt.map(at => ProjectHistoryEntry(at, "erasure-applied", r.id, r.subject)),
          r.failure
            .filter(_ => r.state != ErasureState.Refused)
            .map(reason =>
              ProjectHistoryEntry(
                r.askedAt,
                "erasure-failed",
                r.id,
                r.subject,
                detail = Some(reason)
              )
            ),
          Option.when(r.state == ErasureState.Refused)(
            ProjectHistoryEntry(
              r.askedAt,
              "erasure-refused",
              r.id,
              r.subject,
              Some(r.askedBy),
              r.failure
            )
          )
        ).flatten
      }
      // Newest first; at one instant, the later step of a request's life first.
      .sortBy(e => (e.at, Steps.indexOf(e.kind)))(using
        Ordering[(Instant, Int)].reverse
      )
      .take(Limit)
      .toVector

object ErasureWire:
  given requestErasureCodec: JsonValueCodec[RequestErasure] = Codecs.make[RequestErasure]
  given overrideHoldCodec: JsonValueCodec[OverrideHold]     = Codecs.make[OverrideHold]
  given erasureRequestCodec: JsonValueCodec[ErasureRequest] = Codecs.make[ErasureRequest]
  given erasureRequestsCodec: JsonValueCodec[Vector[ErasureRequest]] =
    Codecs.make[Vector[ErasureRequest]]
  given certificateCodec: JsonValueCodec[ErasureCertificate] = Codecs.make[ErasureCertificate]
  given projectHistoryCodec: JsonValueCodec[Vector[ProjectHistoryEntry]] =
    Codecs.make[Vector[ProjectHistoryEntry]]
