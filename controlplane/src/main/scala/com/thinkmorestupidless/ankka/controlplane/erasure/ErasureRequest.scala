package com.thinkmorestupidless.ankka.controlplane.erasure

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.thinkmorestupidless.ankka.controlplane.domain.Actor
import com.thinkmorestupidless.ankka.core.keyring.HandlerReport

import java.time.Instant

/** Where an erasure request stands (feature 042, `data-model.md`). */
enum ErasureState:
  case Held, Applying, Applied, Withdrawn

  def wire: String = toString.toLowerCase

object ErasureState:
  def parse(value: String): Option[ErasureState] = values.find(_.wire == value)

  // A status word, as a string in every codec that sees it (CLAUDE.md, "a fieldless enum").
  given JsonValueCodec[ErasureState] = new JsonValueCodec[ErasureState]:
    def decodeValue(in: JsonReader, default: ErasureState): ErasureState =
      val text = in.readString(null)
      parse(text).getOrElse(in.decodeError(s"not an erasure state: $text"))
    def encodeValue(x: ErasureState, out: JsonWriter): Unit = out.writeVal(x.wire)
    def nullValue: ErasureState                             = null

/** An owner's override of a hold: who, why, when. */
final case class HoldOverride(owner: Actor, reason: String, at: Option[Instant] = None)

/** What one service did for the erasure, as its instances reported it. */
final case class ServiceCompletion(
    at: Option[Instant] = None,
    rowsRedacted: Long = 0,
    tokensDropped: Long = 0,
    sessionsEnded: Long = 0,
    handler: Option[HandlerReport] = None
)

/** A run of a service's erasure handler that erased something or failed (FR-016). */
final case class HandlerRun(service: String, report: HandlerReport, at: Option[Instant] = None)

/** What an ask carries; the asker is the command's attribution. */
final case class AskErasure(
    project: String,
    subject: String,
    notBefore: Option[Instant] = None,
    reason: Option[String] = None,
    correlation: Option[String] = None
)

final case class ReplaceErasure(notBefore: Option[Instant] = None, reason: Option[String] = None)

final case class OverrideHold(reason: String)

final case class RecordCompletion(service: String, completion: ServiceCompletion)

/** An erasure request (feature 042, R21): one data subject of one project. */
final case class ErasureRequest(
    id: String,
    project: String = "",
    subject: String = "",
    askedBy: Option[Actor] = None,
    askedAt: Option[Instant] = None,
    notBefore: Option[Instant] = None,
    reason: Option[String] = None,
    correlation: Option[String] = None,
    state: Option[ErasureState] = None,
    lapsedAt: Option[Instant] = None,
    holdOverride: Option[HoldOverride] = None,
    erasureSeq: Option[Long] = None,
    destroyedAt: Option[Instant] = None,
    completions: Map[String, ServiceCompletion] = Map.empty,
    handlerRuns: Vector[HandlerRun] = Vector.empty,
    objectsFinalAt: Option[Instant] = None,
    finalAt: Option[Instant] = None,
    withdrawnAt: Option[Instant] = None
):
  def exists: Boolean = state.isDefined

  /** The request's id within its project: `rid` of `<project>/<rid>`. */
  def rid: String = id.substring(id.indexOf('/') + 1)

object ErasureRequest:
  /** At most this long, by FR-014. */
  val ReasonLimit = 500

  /** At most this long, by the data model; in a data subject's alphabet. */
  val CorrelationLimit = 128

  def entityId(project: String, rid: String): String = s"$project/$rid"

/** The events of an erasure request (`data-model.md`), each with who and when. */
enum ErasureRequestEvent:
  case Asked(
      project: String,
      subject: String,
      notBefore: Option[Instant] = None,
      reason: Option[String] = None,
      correlation: Option[String] = None,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case Replaced(
      notBefore: Option[Instant] = None,
      reason: Option[String] = None,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case Withdrawn(actor: Option[Actor] = None, at: Option[Instant] = None)
  case HoldOverridden(reason: String, actor: Option[Actor] = None, at: Option[Instant] = None)

  /** The hold's date passed: the request is applied from here. */
  case HoldLapsed(actor: Option[Actor] = None, at: Option[Instant] = None)
  case LogWritten(seq: Long, actor: Option[Actor] = None, at: Option[Instant] = None)
  case KeyDestroyed(destroyedAt: Instant, actor: Option[Actor] = None, at: Option[Instant] = None)
  case ServiceCompleted(
      service: String,
      completion: ServiceCompletion,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case HandlerRan(
      service: String,
      report: HandlerReport,
      actor: Option[Actor] = None,
      at: Option[Instant] = None
  )
  case ObjectsFinal(finalAt: Instant, actor: Option[Actor] = None, at: Option[Instant] = None)
  case Finalised(actor: Option[Actor] = None, at: Option[Instant] = None)
