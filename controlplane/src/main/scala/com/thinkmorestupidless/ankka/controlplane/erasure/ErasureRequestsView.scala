package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.controlplane.erasure.ErasureRequestEvent.*
import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/**
 * One row per erasure request (feature 042, `data-model.md`'s `erasure_requests`): what a project's
 * list shows, and the lookups by data subject and by correlation. The subject is an identifier the
 * service chose, not a personal field, and is held in the clear as the keyring holds it.
 */
final case class ErasureRequestRow(
    id: String,
    project: String = "",
    rid: String = "",
    subject: String = "",
    state: String = "",
    correlation: Option[String] = None,
    notBefore: Option[Instant] = None,
    askedBy: Option[String] = None,
    askedAt: Option[Instant] = None,
    appliedAt: Option[Instant] = None,
    finalAt: Option[Instant] = None,
    /** When the request's objects are gone for good, once a handler reported a window. */
    objectsFinalAt: Option[Instant] = None,
    /** Every event of the request, for the project's history (FR-016). */
    history: Vector[ErasureHistoryLine] = Vector.empty
)

/** One event of a request as the project's history shows it: what, who, when. */
final case class ErasureHistoryLine(
    kind: String,
    by: Option[String] = None,
    at: Option[Instant] = None,
    detail: Option[String] = None
)

final class ErasureRequestsRowsView extends View[ErasureRequestEvent, ErasureRequestRow]:

  private def row =
    rowState.getOrElse {
      val id = updateContext.subject
      ErasureRequestRow(id, rid = id.substring(id.indexOf('/') + 1))
    }

  def onChange(event: ErasureRequestEvent): Effect =
    val current = row
    val line    = ErasureRequestsView.lineOf(current, event)
    event match
      // A quiet re-run is no line of the project's history.
      case _: RanQuietly => effects.updateRow(current)
      case _             => rowOf(current.copy(history = current.history :+ line), event)

  private def rowOf(row: ErasureRequestRow, event: ErasureRequestEvent): Effect = event match
    case Asked(project, subject, notBefore, _, correlation, actor, at) =>
      val held = notBefore.exists(nb => at.forall(nb.isAfter))
      effects.updateRow(
        row.copy(
          project = project,
          subject = subject,
          state = (if held then ErasureState.Held else ErasureState.Applying).wire,
          correlation = correlation,
          notBefore = notBefore,
          askedBy = actor.map(_.subject),
          askedAt = at
        )
      )
    case Replaced(notBefore, _, _, _) => effects.updateRow(row.copy(notBefore = notBefore))
    case _: Withdrawn => effects.updateRow(row.copy(state = ErasureState.Withdrawn.wire))
    case _: HoldOverridden | _: HoldLapsed =>
      effects.updateRow(row.copy(state = ErasureState.Applying.wire))
    case KeyDestroyed(when, _, _) =>
      effects.updateRow(row.copy(state = ErasureState.Applied.wire, appliedAt = Some(when)))
    case Finalised(_, at)         => effects.updateRow(row.copy(finalAt = at))
    case ObjectsFinal(when, _, _) => effects.updateRow(row.copy(objectsFinalAt = Some(when)))
    case _: ApplyFailed | _: LogWritten | _: ServiceCompleted | _: HandlerRan | _: RanQuietly =>
      effects.updateRow(row)

object ErasureRequestsView
    extends View.Companion[ErasureRequestsRowsView, ErasureRequestEvent, ErasureRequestRow](
      componentId = ComponentId("erasure-requests"),
      source = ChangeSource.eventsOf(ErasureRequestEntity),
      rowSerializer = Codecs.serializer[ErasureRequestRow]("erasure-request-row")
    ):
  def create(ctx: ViewComponentContext) = new ErasureRequestsRowsView

  /** The history line of `event` on request `row`. */
  def lineOf(row: ErasureRequestRow, event: ErasureRequestEvent): ErasureHistoryLine =
    def who(actor: Option[com.thinkmorestupidless.ankka.controlplane.domain.Actor]) =
      actor.map(a => a.display.getOrElse(a.subject))
    val subject = event match
      case a: Asked => a.subject
      case _        => row.subject
    val of = s"erasure ${row.rid} of $subject"
    event match
      case Asked(_, _, notBefore, reason, correlation, actor, at) =>
        val held = notBefore.fold("")(nb => s", held until $nb") + reason.fold("")(r => s" ($r)")
        ErasureHistoryLine(
          "erasure-asked",
          who(actor),
          at,
          Some(of + held + correlation.fold("")(c => s", correlation $c"))
        )
      case Replaced(notBefore, reason, actor, at) =>
        ErasureHistoryLine(
          "erasure-replaced",
          who(actor),
          at,
          Some(of + notBefore.fold("")(nb => s", held until $nb") + reason.fold("")(r => s" ($r)"))
        )
      case Withdrawn(actor, at) => ErasureHistoryLine("erasure-withdrawn", who(actor), at, Some(of))
      case HoldOverridden(reason, actor, at) =>
        ErasureHistoryLine("erasure-hold-overridden", who(actor), at, Some(s"$of: $reason"))
      case HoldLapsed(actor, at) =>
        ErasureHistoryLine("erasure-hold-lapsed", who(actor), at, Some(of))
      case ApplyFailed(reason, actor, at) =>
        ErasureHistoryLine("erasure-apply-failed", who(actor), at, Some(s"$of: $reason"))
      case LogWritten(seq, actor, at) =>
        ErasureHistoryLine("erasure-logged", who(actor), at, Some(s"$of, log entry $seq"))
      case KeyDestroyed(when, actor, at) =>
        ErasureHistoryLine(
          "erasure-key-destroyed",
          who(actor),
          at,
          Some(s"$of, key destroyed at $when")
        )
      case ServiceCompleted(service, c, actor, at) =>
        ErasureHistoryLine(
          "erasure-service-completed",
          who(actor),
          at,
          Some(s"$of, $service: ${c.rowsRedacted} rows redacted, ${c.sessionsEnded} sessions ended")
        )
      case HandlerRan(service, report, actor, at) =>
        val what = report.error.fold(s"${report.objectsErased} objects erased")(e => s"failed: $e")
        ErasureHistoryLine(
          "erasure-handler-ran",
          who(actor),
          at,
          Some(s"$of, $service's handler $what")
        )
      case ObjectsFinal(when, actor, at) =>
        ErasureHistoryLine(
          "erasure-objects-final",
          who(actor),
          at,
          Some(s"$of, objects final at $when")
        )
      case Finalised(actor, at) => ErasureHistoryLine("erasure-final", who(actor), at, Some(of))
      case RanQuietly(ranAt, actor, at, _) =>
        ErasureHistoryLine("erasure-ran-quietly", who(actor), at, Some(s"$of, re-run at $ranAt"))

  val byProject = query("by-project")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'project' = :project " +
      "ORDER BY payload::jsonb->>'askedAt' DESC"
  )
  val bySubject = query("by-subject")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'project' = :project " +
      "AND payload::jsonb->>'subject' = :subject ORDER BY payload::jsonb->>'askedAt' DESC"
  )

  /**
   * What the sweep carries on (T059): held, applying, or applied and not yet final, while applied
   * since `since` (the re-run window's start) or while its objects' date is no more than a window
   * past. One past both waits on a service that has not carried it out; it is read no more, and
   * says so on its page and its certificate.
   */
  val open = query("open")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'state' = 'held' " +
      "OR payload::jsonb->>'state' = 'applying' " +
      "OR (payload::jsonb->>'state' = 'applied' AND payload::jsonb->>'finalAt' IS NULL " +
      "AND (CAST(payload::jsonb->>'appliedAt' AS timestamptz) >= CAST(:since AS timestamptz) " +
      "OR CAST(payload::jsonb->>'objectsFinalAt' AS timestamptz) >= CAST(:since AS timestamptz)))"
  )

  /** Every request applied since `since`, whose handlers the sweep runs again (FR-015). */
  val applied = query("applied")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'state' = 'applied' " +
      "AND CAST(payload::jsonb->>'appliedAt' AS timestamptz) >= CAST(:since AS timestamptz)"
  )

  val byCorrelation = query("by-correlation")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'correlation' = :correlation " +
      "ORDER BY payload::jsonb->>'askedAt' DESC"
  )
