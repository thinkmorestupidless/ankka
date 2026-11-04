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
    finalAt: Option[Instant] = None
)

final class ErasureRequestsRowsView extends View[ErasureRequestEvent, ErasureRequestRow]:

  private def row =
    rowState.getOrElse {
      val id = updateContext.subject
      ErasureRequestRow(id, rid = id.substring(id.indexOf('/') + 1))
    }

  def onChange(event: ErasureRequestEvent): Effect = event match
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
    case Finalised(_, at) => effects.updateRow(row.copy(finalAt = at))
    case _: LogWritten | _: ServiceCompleted | _: HandlerRan | _: ObjectsFinal => effects.ignore()

object ErasureRequestsView
    extends View.Companion[ErasureRequestsRowsView, ErasureRequestEvent, ErasureRequestRow](
      componentId = ComponentId("erasure-requests"),
      source = ChangeSource.eventsOf(ErasureRequestEntity),
      rowSerializer = Codecs.serializer[ErasureRequestRow]("erasure-request-row")
    ):
  def create(ctx: ViewComponentContext) = new ErasureRequestsRowsView

  val byProject = query("by-project")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'project' = :project " +
      "ORDER BY payload::jsonb->>'askedAt' DESC"
  )
  val bySubject = query("by-subject")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'project' = :project " +
      "AND payload::jsonb->>'subject' = :subject ORDER BY payload::jsonb->>'askedAt' DESC"
  )
  val byCorrelation = query("by-correlation")(
    s"SELECT payload FROM $table WHERE payload::jsonb->>'correlation' = :correlation " +
      "ORDER BY payload::jsonb->>'askedAt' DESC"
  )
