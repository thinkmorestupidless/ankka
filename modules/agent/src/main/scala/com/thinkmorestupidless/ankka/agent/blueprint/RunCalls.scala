package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.{Json, TokenUsage}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.runtime.{SqlSyntax, ViewClient}
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import scala.concurrent.duration.*

/** One step of a run, as a reader sees it. */
final case class StepSnapshot(
    name: String,
    ended: Boolean,
    result: Option[Json],
    sessions: Vector[String],
    usage: TokenUsage,
    judgmentUsage: TokenUsage,
    modelCalls: Int,
    waiting: Vector[ApprovalRef],
    items: Vector[ItemRecord],
    rounds: Vector[RoundRecord]
)

/** A run as its record says it is. */
final case class RunSnapshot(
    runId: String,
    blueprint: String,
    version: Int,
    input: Json,
    status: RunStatus,
    step: Option[String],
    steps: Vector[StepSnapshot],
    usage: TokenUsage,
    judgmentUsage: TokenUsage,
    modelCalls: Int,
    startedBy: String,
    startedAt: Long,
    endedAt: Option[Long],
    reason: Option[String]
):
  def ended: Boolean                                = status.ended
  def stepNamed(name: String): Option[StepSnapshot] = steps.find(_.name == name)

object RunSnapshot:
  private[blueprint] def of(record: RunRecord): RunSnapshot =
    def json(text: String): Json = Json.parse(text).getOrElse(Json.Str(text))
    RunSnapshot(
      record.runId,
      record.blueprint,
      record.version,
      json(record.input),
      record.status,
      record.current.map(_.name),
      record.steps.map(s =>
        StepSnapshot(
          s.name,
          s.ended,
          s.result.map(json),
          s.sessions,
          s.usage,
          s.judgmentUsage,
          s.modelCalls,
          s.waiting,
          s.items,
          s.rounds
        )
      ),
      record.usage,
      record.judgmentUsage,
      record.modelCalls,
      record.startedBy,
      record.startedAt,
      record.endedAt,
      record.reason
    )

/**
 * Calls about runs: start one, read it, wait for it, cancel it, list a blueprint's. Reached as
 * `agents.runs`, once the service has started.
 */
final class RunCalls private[ankka] (
    client: ComponentClient,
    blueprints: BlueprintCalls,
    views: Option[ViewClient]
):

  private def entity(runId: String) = client.forEventSourcedEntity(EntityId(runId))

  /** Who started a run when the caller does not say. */
  val Caller: String = "caller"

  /**
   * Starts a run of the blueprint's current version. The input is checked against the version's
   * input shape first; a run already held under the id with the same blueprint and input is
   * answered as it stands, and one with another is `Conflict`.
   */
  def start(name: String, input: Json, runId: String, startedBy: String = Caller): RunSnapshot =
    if runId.startsWith(RunEntity.SchedulePrefix) then
      throw CommandError(
        s"run ids starting '${RunEntity.SchedulePrefix}' are the platform's",
        ErrorCode.BadRequest
      )
    val versions = blueprints.versions(name)
    val current =
      versions.lastOption.getOrElse(throw CommandError(s"no blueprint '$name'", ErrorCode.NotFound))
    val held     = blueprints.version(name, current.number)
    val problems = held.input.check(input)
    if problems.nonEmpty then
      throw CommandError(
        s"the input does not have the input shape of '$name': ${problems.mkString("; ")}",
        ErrorCode.BadRequest
      )
    startHeld(held, current.number, input, runId, startedBy)

  private[ankka] def startHeld(
      held: Blueprint,
      version: Int,
      input: Json,
      runId: String,
      startedBy: String
  ): RunSnapshot =
    val deadline = held.timeLimit.map(t => System.currentTimeMillis() + t.toMillis)
    val record = entity(runId)
      .call(RunEntity.start)
      .invoke(RunEntity.Start(held.name, version, input.render, startedBy, deadline))
    // Wake the host; a host that was remembered is awake already, and a poke does it no harm.
    ComponentClient.await(
      client.transportRef.ask(
        RunHost.ComponentId,
        EntityId(runId),
        MethodName(RunHost.Start),
        Array.emptyByteArray,
        Metadata.empty
      ),
      client.transportRef.askTimeout
    ): Unit
    RunSnapshot.of(record)

  def get(runId: String): RunSnapshot = RunSnapshot.of(entity(runId).call(RunEntity.get).invoke())

  /**
   * Waits until the run has completed, failed or been cancelled; `Timeout` when it has not by then.
   */
  def await(runId: String, timeout: FiniteDuration = 30.minutes): RunSnapshot =
    val deadline = System.nanoTime() + timeout.toNanos
    var latest   = get(runId)
    while !latest.ended && System.nanoTime() < deadline do
      Thread.sleep(250)
      latest = get(runId)
    if latest.ended then latest
    else throw CommandError(s"run '$runId' had not ended after $timeout", ErrorCode.Timeout)

  /** Asks the run to stop: the turn in progress ends, and nothing starts after it. */
  def cancel(runId: String, by: String = Caller): RunSnapshot =
    val record = entity(runId).call(RunEntity.cancel).invoke(RunEntity.Cancel(by))
    if !record.status.ended then
      ComponentClient.await(
        client.transportRef.ask(
          RunHost.ComponentId,
          EntityId(runId),
          MethodName(RunHost.Start),
          Array.emptyByteArray,
          Metadata.empty
        ),
        client.transportRef.askTimeout
      ): Unit
    RunSnapshot.of(record)

  /**
   * A blueprint's runs, oldest first, with the version each ran. Needs the service's projections.
   */
  def list(name: String): Vector[RunSummary] =
    views match
      case None =>
        throw CommandError(
          "listing runs needs the service's projections: register ProjectionRuntime()",
          ErrorCode.Internal
        )
      case Some(client) =>
        import SqlSyntax.sql
        client
          .forView(RunsView)
          .ordered(
            SqlSyntax.jsonText("blueprint") ++ sql" = $name",
            SqlSyntax.jsonNumber("startedAt")
          )
