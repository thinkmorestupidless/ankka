package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.core.{Codecs, ComponentId}
import com.thinkmorestupidless.ankka.sdk.*

/** One run as a reader lists it. */
final case class RunSummary(
    runId: String,
    blueprint: String,
    version: Int,
    status: RunStatus,
    startedBy: String,
    startedAt: Long,
    endedAt: Option[Long] = None
)

/**
 * The runs of a service, one row each, so a blueprint's runs can be listed with the version each
 * ran.
 */
final class RunsView extends View[RunEvent, RunSummary]:

  def onChange(event: RunEvent): Effect = event match
    case RunEvent.Started(blueprint, version, _, by, _, at) =>
      effects.updateRow(
        RunSummary(updateContext.subject, blueprint, version, RunStatus.Running, by, at)
      )
    case RunEvent.WaitingForDecision(_, _, _) =>
      rowState.fold(effects.ignore())(r =>
        effects.updateRow(r.copy(status = RunStatus.WaitingForDecision))
      )
    case RunEvent.DecisionReceived(_, _, _) =>
      rowState.fold(effects.ignore())(r => effects.updateRow(r.copy(status = RunStatus.Running)))
    case RunEvent.Ended(status, _, at) =>
      rowState.fold(effects.ignore())(r =>
        effects.updateRow(r.copy(status = status, endedAt = Some(at)))
      )
    case _ => effects.ignore()

object RunsView
    extends View.Companion[RunsView, RunEvent, RunSummary](
      ComponentId("ankka-blueprint-runs"),
      ChangeSource.eventsOf(RunEntity),
      Codecs.serializer[RunSummary]("blueprint-run-summary")
    ):
  def create(ctx: ViewComponentContext) = new RunsView

  val platformDescriptor: ViewDescriptor[RunsView, RunEvent, RunSummary] =
    descriptor.copy(platform = true)
