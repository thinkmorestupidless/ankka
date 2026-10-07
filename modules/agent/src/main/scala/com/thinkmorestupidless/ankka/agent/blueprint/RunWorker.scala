package com.thinkmorestupidless.ankka.agent.blueprint

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, SessionId}
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import org.slf4j.LoggerFactory

import java.util.concurrent.{ConcurrentHashMap, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Carries a run out on one virtual thread, with one more for each step in flight. A blueprint's
 * steps form a graph, each reading the run's input or steps before it; a step runs once everything
 * it reads has ended, so steps that read only what has ended run at once, and the blueprint's order
 * breaks ties. The record is re-read at every boundary and nothing is kept in memory across one, so
 * a worker that stops anywhere picks up from the record: an ended step is never done again, and an
 * ask turn already ended in its session is taken as the answer without a call.
 */
private[ankka] final class RunWorker(
    runId: String,
    client: ComponentClient,
    private[blueprint] val registry: BlueprintRegistry,
    reportStopped: () => Unit
):
  import RunEvent as E

  private val log      = LoggerFactory.getLogger("ankka.blueprints")
  private val wake     = LinkedBlockingQueue[Unit]()
  private val inFlight = ConcurrentHashMap.newKeySet[String]()

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
            case run => schedule(run)
        catch
          case e: CommandError if e.code == ErrorCode.NotFound =>
            pause(1.second) // woken by a caller before its record was written
          case _: InterruptedException => running = false
          case NonFatal(e) =>
            log.warn(s"run '$runId' hit a fault; looking at its record again in a second", e)
            pause(1.second)
    catch case _: InterruptedException => ()
    finally log.info("run '{}': worker stopped", runId)

  // ── The graph ─────────────────────────────────────────────────────────────

  /** Starts every step whose reads have ended, then waits for one to end or for a poke. */
  private def schedule(run: RunRecord): Unit =
    val blueprint = blueprintOf(run)
    val ended     = run.steps.filter(_.ended).map(_.name).toSet
    val left      = blueprint.steps.filterNot(s => ended(s.name))
    if !endIfDue(run, blueprint, left.nonEmpty) then
      if left.isEmpty then end(RunStatus.Completed, None)
      else
        val ready = left
          .filterNot(s => inFlight.contains(s.name))
          .filter(_.allReads.forall(r => r == "input" || ended(sourceOf(r))))
        ready.foreach { s =>
          inFlight.add(s.name): Unit
          if run.step(s.name).isEmpty then
            log.info("run '{}': step '{}' starts", runId, s.name)
            record(E.StepStarted(s.name, now()))
          Thread.ofVirtual().name(s"run-$runId-${s.name}").start { () =>
            try stepOnce(run, blueprint, s)
            catch
              case e: CommandError if e.code == ErrorCode.Conflict =>
                log.debug("run '{}': step '{}' ended after the run did", runId, s.name)
              case _: InterruptedException => ()
              case NonFatal(e) =>
                log.warn(s"run '$runId': step '${s.name}' hit a fault", e)
            finally
              inFlight.remove(s.name): Unit
              poke()
          }: Unit
        }
        if ready.isEmpty && inFlight.isEmpty then
          // Every step left reads something that will never end; the check refuses this, so it
          // is a fault of the record, and the run cannot go on.
          end(
            RunStatus.Failed,
            Some(
              s"no step can start: ${left.map(_.name).mkString(", ")} wait on what has not ended"
            )
          )
        else pause(1.second)

  private def sourceOf(read: String): String = read.split("\\.", 2).head

  /** Cancelled, past the deadline, or over budget with steps left: ends the run and says so. */
  private def endIfDue(run: RunRecord, blueprint: Blueprint, stepsLeft: Boolean): Boolean =
    run.cancelRequested match
      case Some(by) =>
        refuseWaiting(run, s"run '$runId' was cancelled by $by")
        end(RunStatus.Cancelled, Some(s"cancelled by $by"))
        true
      case None if run.deadline.exists(_ <= now()) =>
        refuseWaiting(run, s"run '$runId' passed its time limit")
        end(RunStatus.Failed, Some("the run passed its time limit"))
        true
      case None if stepsLeft && blueprint.runBudget.exists(_ <= run.modelCalls) =>
        end(
          RunStatus.Failed,
          Some(s"the run budget of ${blueprint.runBudget.get} model calls is spent")
        )
        true
      case None => false

  private def end(status: RunStatus, reason: Option[String]): Unit =
    log.info("run '{}' ends {}{}", runId, status.wire, reason.fold("")(r => s": $r"))
    record(E.Ended(status, reason, now()))

  // ── One step ──────────────────────────────────────────────────────────────

  /** Carries one step out, by what it does, how it repeats and until what. */
  private def stepOnce(run: RunRecord, blueprint: Blueprint, step: Step): Unit =
    (step.does, step.over, step.until) match
      case (Action.Ask(Some(workerName)), Over.Once, None) =>
        askStep(run, blueprint, step, workerName)
      case (Action.Call(handler), Over.Once, None) => callStep(run, step, handler)
      case _ => end(RunStatus.Failed, Some(s"${step.name}: this shape of step is not built yet"))

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

  /** A registered handler, given what the step reads; what it returns is the step's result. */
  private def callStep(run: RunRecord, step: Step, handlerName: String): Unit =
    val handler =
      registry
        .handler(handlerName)
        .getOrElse(throw IllegalStateException(s"no handler '$handlerName'"))
    val ref   = RunRef(runId, step.name, run.blueprint, run.version)
    val input = Reads.values(step, run)
    val result =
      try Right(RunContext.within(ref)(handler.run(ref, input)))
      catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString))
    result match
      case Left(failure) =>
        end(RunStatus.Failed, Some(s"${step.name}: the handler '$handlerName' failed: $failure"))
      case Right(json) =>
        val problems = step.result.check(json)
        if problems.nonEmpty then
          end(
            RunStatus.Failed,
            Some(
              s"${step.name}: the handler '$handlerName' did not answer with the step's shape: ${problems.mkString("; ")}"
            )
          )
        else
          record(
            E.StepEnded(
              step.name,
              json.render,
              Vector.empty,
              TokenUsage.zero,
              TokenUsage.zero,
              0,
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
      Thread.sleep(delay.toMillis)
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

  private[blueprint] def stepsInFlight: Set[String] = inFlight.asScala.toSet

private[blueprint] object RunWorker:
  /** `after` less `before`, field by field: what a step spent, read from its session's totals. */
  def minus(after: TokenUsage, before: TokenUsage): TokenUsage =
    TokenUsage(
      after.inputTokens - before.inputTokens,
      after.outputTokens - before.outputTokens,
      after.cacheReadTokens - before.cacheReadTokens,
      after.cacheWriteTokens - before.cacheWriteTokens
    )
