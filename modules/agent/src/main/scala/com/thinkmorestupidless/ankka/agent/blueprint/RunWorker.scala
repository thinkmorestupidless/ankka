package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, SessionId}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.slf4j.LoggerFactory

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/**
 * Carries a run out, step by step, on one virtual thread. The record is re-read at every boundary
 * and nothing is kept in memory across one, so a worker that stops anywhere picks up from the
 * record: an ended step is never done again, and an ask turn already ended in its session is taken
 * as the answer without a call.
 */
private[ankka] final class RunWorker(
    runId: String,
    client: ComponentClient,
    private[blueprint] val registry: BlueprintRegistry,
    reportStopped: () => Unit
):
  import RunEvent as E

  private val log  = LoggerFactory.getLogger("ankka.blueprints")
  private val wake = LinkedBlockingQueue[Unit]()

  @volatile private var running        = true
  @volatile private var thread: Thread = null

  def start(): Unit =
    thread = Thread.ofVirtual().name(s"run-$runId").start(() => runLoop())

  /** Wakes the worker to look at the record again, now. */
  def poke(): Unit = wake.offer(()): Unit

  def stop(): Unit =
    running = false
    Option(thread).foreach(_.interrupt())

  private[blueprint] def pause(d: FiniteDuration): Unit =
    wake.poll(d.toMillis, TimeUnit.MILLISECONDS): Unit

  private def entity                               = client.forEventSourcedEntity(EntityId(runId))
  private[blueprint] def read(): RunRecord         = entity.call(RunEntity.get).invoke()
  private[blueprint] def record(e: RunEvent): Unit = entity.call(RunEntity.record).invoke(e): Unit
  private[blueprint] def now(): Long               = System.currentTimeMillis()

  private def runLoop(): Unit =
    log.info("run '{}': worker started", runId)
    try
      while running do
        try
          read() match
            case run if run.status.ended =>
              log.info("run '{}' has ended ({}); the worker stops", runId, run.status.wire)
              running = false
              reportStopped()
            case run => step(run)
        catch
          case e: CommandError if e.code == ErrorCode.NotFound =>
            pause(1.second) // woken by a caller before its record was written
          case _: InterruptedException => running = false
          case NonFatal(e) =>
            log.warn(s"run '$runId' hit a fault; looking at its record again in a second", e)
            pause(1.second)
    catch case _: InterruptedException => ()
    finally log.info("run '{}': worker stopped", runId)

  // ── One step at a time ──────────────────────────────────────────────────────

  private def step(run: RunRecord): Unit =
    val blueprint = blueprintOf(run)
    val next      = blueprint.steps.find(s => !run.step(s.name).exists(_.ended))
    if !endIfDue(run, blueprint, next) then
      next match
        case None => end(RunStatus.Completed, None)
        case Some(s) =>
          if run.step(s.name).isEmpty then
            log.info("run '{}': step '{}' starts", runId, s.name)
            record(E.StepStarted(s.name, now()))
          s.pattern match
            case Pattern.Ask(workerName) => askStep(run, blueprint, s, workerName)
            case other =>
              val name =
                BlueprintCheck.PatternNames.getOrElse(other.productPrefix, other.productPrefix)
              end(RunStatus.Failed, Some(s"${s.name}: the pattern $name is not built yet"))

  /** Cancelled, past the deadline, or over budget with steps left: ends the run and says so. */
  private def endIfDue(run: RunRecord, blueprint: Blueprint, next: Option[Step]): Boolean =
    run.cancelRequested match
      case Some(by) =>
        refuseWaiting(run, s"run '$runId' was cancelled by $by")
        end(RunStatus.Cancelled, Some(s"cancelled by $by"))
        true
      case None if run.deadline.exists(_ <= now()) =>
        refuseWaiting(run, s"run '$runId' passed its time limit")
        end(RunStatus.Failed, Some("the run passed its time limit"))
        true
      case None if next.isDefined && blueprint.runBudget.exists(_ <= run.modelCalls) =>
        end(
          RunStatus.Failed,
          Some(s"the run budget of ${blueprint.runBudget.get} model calls is spent")
        )
        true
      case None => false

  private def end(status: RunStatus, reason: Option[String]): Unit =
    log.info("run '{}' ends {}{}", runId, status.wire, reason.fold("")(r => s": $r"))
    record(E.Ended(status, reason, now()))

  private def askStep(run: RunRecord, blueprint: Blueprint, step: Step, workerName: String): Unit =
    val worker =
      blueprint.workers
        .find(_.name == workerName)
        .getOrElse(throw IllegalStateException(s"no worker '$workerName'"))
    val session = s"run:$runId:${step.name}:$workerName"
    val ref     = RunRef(runId, step.name, blueprint.name, run.version)
    val message = Reads.message(step, run)
    val before  = history(session)

    Turns.answer(this, session, worker, message, step.result, ref, step.name) match
      case Turns.Interrupted     => () // the record says why; the next look at it ends the run
      case Turns.Failed(failure) => end(RunStatus.Failed, Some(s"${step.name}: $failure"))
      case Turns.Answered(result) =>
        val after = history(session)
        record(
          E.StepEnded(
            step.name,
            result.render,
            Vector(session),
            RunWorker.minus(after.usage, before.usage),
            RunWorker.minus(after.judgmentUsage, before.judgmentUsage),
            Turns.modelCalls(after) - Turns.modelCalls(before),
            now()
          )
        )

  // ── Shared by the patterns ────────────────────────────────────────────────

  private[blueprint] def history(session: String): SessionHistory =
    client.forEventSourcedEntity(EntityId(session)).call(SessionMemoryEntity.history).invoke()

  private[blueprint] def agent(session: String) = client.forAgent(SessionId(session))

  private[blueprint] def blueprintOf(run: RunRecord): Blueprint =
    val held =
      client.forEventSourcedEntity(EntityId(run.blueprint)).call(BlueprintEntity.get).invoke()
    held
      .version(run.version)
      .map(v => Blueprint.fromJson(v.canonical).fold(e => throw IllegalStateException(e), identity))
      .getOrElse(
        throw IllegalStateException(s"blueprint '${run.blueprint}' has no version ${run.version}")
      )

  /**
   * Waits for a decision on the session's requests, backing off from a second to thirty. `true`
   * when the decision came; `false` when the run was cancelled or passed its deadline first, which
   * the next look at the record ends.
   */
  private[blueprint] def waitForDecision(
      stepName: String,
      session: String,
      requests: Vector[ApprovalRequest]
  ): Boolean =
    requests.foreach(r =>
      record(E.WaitingForDecision(stepName, ApprovalRef(session, r.id, r.tool), now()))
    )
    var delay                    = 1.second
    var outcome: Option[Boolean] = None
    while running && outcome.isEmpty do
      pause(delay)
      val latest = read()
      if latest.cancelRequested.isDefined || latest.deadline.exists(_ <= now()) then
        outcome = Some(false)
      else if history(session).awaiting.isEmpty then
        requests.foreach(r => record(E.DecisionReceived(stepName, r.id, now())))
        outcome = Some(true)
      else delay = (delay * 2).min(30.seconds)
    outcome.getOrElse(false)

  /**
   * Refuses, as the platform, every request the run is waiting on, so a later decision runs
   * nothing.
   */
  private def refuseWaiting(run: RunRecord, note: String): Unit =
    run.steps.filter(_.waiting.nonEmpty).foreach { s =>
      s.waiting.foreach { w =>
        try
          agent(w.session).decideAsPlatform(
            AskAgent.componentId,
            Decision.refused(w.approvalId, Decision.Platform, note)
          )
        catch
          case e: CommandError =>
            log.warn(
              s"run '$runId': refusing approval ${w.approvalId} answered ${e.code}: ${e.getMessage}"
            )
      }
      record(E.ApprovalsRefused(s.name, s.waiting.map(_.approvalId), now()))
    }

private[blueprint] object RunWorker:
  /** `after` less `before`, field by field: what a step spent, read from its session's totals. */
  def minus(after: TokenUsage, before: TokenUsage): TokenUsage =
    TokenUsage(
      after.inputTokens - before.inputTokens,
      after.outputTokens - before.outputTokens,
      after.cacheReadTokens - before.cacheReadTokens,
      after.cacheWriteTokens - before.cacheWriteTokens
    )
