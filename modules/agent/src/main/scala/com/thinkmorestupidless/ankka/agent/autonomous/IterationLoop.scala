package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.judgment.{JudgmentScriptFailed, Judgments}
import com.thinkmorestupidless.ankka.core.{EntityId, SessionId}
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * One autonomous agent instance's work on one task, an iteration at a time.
 *
 * Straight-line blocking code, run on a virtual thread, like the request agent's loop. It writes
 * everything it does through the component client — the instance's record, the task's record and
 * the task's session in session memory — and reports what happened through `emit`, so it never
 * touches the actor that hosts it.
 *
 * What is recorded, and in what order, is what makes a crash survivable (see `resumePoint`). The
 * task's own record is not written here: the loop answers what the model decided, and the host
 * writes it, so that one place deals with a task that ended some other way meanwhile.
 *
 *   1. the instance records that iteration n started, at a time later than anything in the session;
 *   2. the model is called;
 *   3. its response is appended to the task's session;
 *   4. the instance records that iteration n completed;
 *   5. the tools the response asked for run and their results are appended — or, for the built-in
 *      `complete_task` and `fail_task`, the task's record is written.
 *
 * A model call that was recorded is never made again. A tool may be: a crash between 3 and 5 runs
 * the recorded response's tools again on resumption, so tools are run at least once, not exactly
 * once, and one with a side effect should tolerate a repeat.
 */
private[ankka] final class IterationLoop(
    definition: AutonomousAgentDefinition,
    agent: AutonomousAgent,
    instanceId: String,
    client: ComponentClient,
    model: ModelProvider,
    modelTimeout: FiniteDuration,
    judgments: Judgments,
    emit: Notification => Unit
):
  import IterationLoop.*

  private val componentId = agent.context.componentId
  private val role        = componentId.toString
  private val tools       = agent.tools.map(t => t.name -> t).toMap

  private def instance =
    client.forEventSourcedEntity(InstanceEntity.idFor(componentId, instanceId))

  private def session(taskId: String) = client.forEventSourcedEntity(EntityId(sessionIdFor(taskId)))

  private def now(): Long = System.currentTimeMillis()

  private def record(event: InstanceEvent): InstanceRecord =
    instance.call(InstanceEntity.record).invoke(event)

  // ── Where to pick up ──────────────────────────────────────────────────────

  /**
   * Where the work on a task stands, read from the instance's record and the last message in the
   * task's session. Only the last message is read, so compaction — which keeps recent messages
   * verbatim — cannot confuse it.
   */
  def resumePoint(working: Working): ResumePoint =
    val tail =
      session(working.taskId).call(SessionMemoryEntity.history).invoke().messages.lastOption
    def landed = tail.exists {
      case m: SessionMessage.AiMessage => m.timestamp >= working.iterationStartedAt
      case _                           => false
    }
    if working.iteration == 0 then ResumePoint.NextIteration(1)
    else if working.completed < working.iteration then
      // Started and not recorded as completed: did the response reach the session first?
      if landed then ResumePoint.RecordCompletion(working.iteration)
      else ResumePoint.CallModel(working.iteration)
    else
      tail match
        case Some(m: SessionMessage.AiMessage) if m.toolCalls.nonEmpty =>
          ResumePoint.RunTools(working.iteration, m)
        case _ => ResumePoint.NextIteration(working.iteration + 1)

  // ── One iteration ─────────────────────────────────────────────────────────

  /**
   * Carries the task on from `point` to the end of that iteration.
   *
   * `dependencies` are the completed dependencies' results, shown to the model on every iteration;
   * `budget` is the task type's iteration budget.
   */
  def run(
      point: ResumePoint,
      record: TaskRecord,
      taskType: TaskType[?],
      budget: Int,
      dependencies: Vector[DependencyResult]
  ): IterationResult =
    point match
      case ResumePoint.NextIteration(n) =>
        if n > budget then
          IterationResult.Ended(TaskOutcome.Failed(s"iteration budget of $budget exhausted"))
        else
          val startedAt = startIteration(record.id, n, budget)
          callModel(record, taskType, n, budget, startedAt, dependencies)
      case ResumePoint.CallModel(n) =>
        callModel(record, taskType, n, budget, workingStartedAt(), dependencies)
      case ResumePoint.RecordCompletion(n) =>
        val response = lastResponse(record.id)
        completeIteration(record, taskType, n, response, TokenUsage.zero)
      case ResumePoint.RunTools(n, message) =>
        runTools(record, taskType, n, message)

  private def workingStartedAt(): Long =
    instance.call(InstanceEntity.get).invoke().current.map(_.iterationStartedAt).getOrElse(now())

  private def lastResponse(taskId: String): SessionMessage.AiMessage =
    session(taskId)
      .call(SessionMemoryEntity.history)
      .invoke()
      .messages
      .reverseIterator
      .collectFirst { case m: SessionMessage.AiMessage => m }
      .getOrElse(throw IllegalStateException(s"task '$taskId' has no recorded response"))

  private def startIteration(taskId: String, n: Int, budget: Int): Long =
    // Later than anything already in the session, so a response found there at or after this time
    // can only be this iteration's.
    val latest = session(taskId).call(SessionMemoryEntity.history).invoke().messages.lastOption
    val at     = math.max(now(), latest.fold(0L)(_.timestamp + 1))
    record(InstanceEvent.IterationStarted(n, at))
    emit(Notification.IterationStarted(role, instanceId, taskId, n, budget - n, at))
    at

  private def callModel(
      taskRecord: TaskRecord,
      taskType: TaskType[?],
      n: Int,
      budget: Int,
      startedAt: Long,
      dependencies: Vector[DependencyResult]
  ): IterationResult =
    val request = ModelRequest(
      settings = ModelSettings(model.modelName),
      systemMessage = Some(systemMessage(taskType, n, budget)),
      messages = firstTurn(taskRecord, dependencies) +: PromptReplay.replay(history(taskRecord.id)),
      tools = agent.tools.map(_.spec).toVector ++ builtIns(taskType)
    )
    val response =
      try Right(Await.result(model.complete(request), modelTimeout))
      catch
        case failure: ModelCallFailed if model.name == "test" =>
          // A scripted model that has run out is a test that is no longer testing what it says:
          // fail the task now, naming it, rather than retrying into a stall.
          return IterationResult.Ended(TaskOutcome.Failed(failure.getMessage))
        case _: java.util.concurrent.TimeoutException =>
          Left(s"${model.name} did not respond within $modelTimeout")
        case failure: InterruptedException => throw failure
        case NonFatal(failure) =>
          Left(Option(failure.getMessage).getOrElse(failure.toString))

    response match
      case Left(error) => failed(taskRecord.id, n, error)
      case Right(r) =>
        val message = SessionMessage.AiMessage(
          math.max(now(), startedAt),
          r.text,
          role,
          r.toolCalls.map(c => RecordedToolCall(c.id, c.name, c.arguments.render))
        )
        session(taskRecord.id)
          .call(SessionMemoryEntity.append)
          .invoke(SessionMemoryEntity.Append(Vector(message), r.usage)): Unit
        completeIteration(taskRecord, taskType, n, message, r.usage)

  private def completeIteration(
      taskRecord: TaskRecord,
      taskType: TaskType[?],
      n: Int,
      message: SessionMessage.AiMessage,
      usage: TokenUsage
  ): IterationResult =
    val at = now()
    record(InstanceEvent.IterationCompleted(n, usage, at))
    emit(Notification.IterationCompleted(role, instanceId, taskRecord.id, n, usage, at))
    runTools(taskRecord, taskType, n, message)

  /** Records that iteration n failed; the host pauses, then tries it again. */
  private[autonomous] def failed(taskId: String, n: Int, error: String): IterationResult =
    val at = now()
    record(InstanceEvent.IterationFailed(n, error, at))
    emit(Notification.IterationFailed(role, instanceId, taskId, n, error, at))
    IterationResult.Faulted(error)

  // ── Tools ─────────────────────────────────────────────────────────────────

  private def runTools(
      taskRecord: TaskRecord,
      taskType: TaskType[?],
      n: Int,
      message: SessionMessage.AiMessage
  ): IterationResult =
    val calls = message.toolCalls.map(c =>
      ToolCall(c.id, c.name, Json.parse(c.arguments).getOrElse(Json.Obj(Map.empty)))
    )
    calls.find(c => BuiltIns.contains(c.name)) match
      case Some(builtIn) =>
        // The first built-in decides. Nothing else in the response runs: the model has said it is
        // done, or that it cannot be.
        val others = calls.filterNot(_ eq builtIn).map { c =>
          ToolResult(c.id, c.name, s"not run: ${builtIn.name} ended the iteration", isError = true)
        }
        if builtIn.name == AutonomousAgent.FailTask then
          val reason =
            builtIn.arguments("reason").flatMap(_.asString).getOrElse("the agent gave up")
          IterationResult.Ended(TaskOutcome.Failed(reason))
        else
          // A check that throws has decided nothing: the iteration failed, and trying it again
          // checks the same recorded result again — the model is not asked for another.
          try complete(taskRecord, taskType, builtIn, others)
          catch
            case failure: InterruptedException => throw failure
            // A scripted judgment that cannot answer is the test's mistake: end the task now,
            // naming it, rather than retrying into a stall.
            case failure: JudgmentScriptFailed =>
              IterationResult.Ended(TaskOutcome.Failed(failure.getMessage))
            case NonFatal(failure) =>
              failed(
                taskRecord.id,
                n,
                s"checking the result failed: ${Option(failure.getMessage).getOrElse(failure.toString)}"
              )
      case None =>
        val results = calls.map(c => PromptReplay.runTool(tools, c))
        appendResults(taskRecord.id, results)
        IterationResult.Continue

  private def complete(
      taskRecord: TaskRecord,
      taskType: TaskType[?],
      call: ToolCall,
      others: Vector[ToolResult]
  ): IterationResult =
    def refuse(message: String): Unit =
      appendResults(
        taskRecord.id,
        ToolResult(call.id, call.name, message, isError = true) +: others
      )
    taskType.verify(call.arguments) match
      case TaskType.Verdict.Malformed(problem) =>
        // A result that does not decode is the model's to correct, not the task's end.
        refuse(s"the result does not match the task's shape: $problem")
        IterationResult.Continue
      case TaskType.Verdict.Rejected(rule, reason) =>
        val because = s"rule '$rule': $reason"
        refuse(s"result rejected — $because")
        IterationResult.Rejected(because)
      case TaskType.Verdict.Accepted(encoded) =>
        checkGuardrails(encoded, Guardrails.Direction.Output) match
          case Some(because) =>
            refuse(s"result rejected — $because")
            IterationResult.Rejected(because)
          case None => IterationResult.Completed(encoded)

  private def appendResults(taskId: String, results: Vector[ToolResult]): Unit =
    if results.nonEmpty then
      val at = now()
      val messages = results.map(r =>
        SessionMessage.ToolResultMessage(at, r.callId, r.name, r.content, r.isError, role)
      )
      session(taskId)
        .call(SessionMemoryEntity.append)
        .invoke(SessionMemoryEntity.Append(messages, TokenUsage.zero)): Unit

  // ── The request ───────────────────────────────────────────────────────────

  private def history(taskId: String): Vector[SessionMessage] =
    session(taskId).call(SessionMemoryEntity.history).invoke().messages

  private def systemMessage(taskType: TaskType[?], n: Int, budget: Int): String =
    val remaining = budget - n
    val warning =
      if remaining <= 0 then " This is your last iteration: complete or fail the task now."
      else if n >= math.ceil(definition.settings.approachingBudgetAt * budget) then
        s" You have $remaining ${
            if remaining == 1 then "iteration" else "iterations"
          } left after this one; finish if you can."
      else ""
    Seq(
      Some(definition.description),
      definition.instructions,
      Some(s"You are working on a task of type '${taskType.name}': ${taskType.description}"),
      Some(
        "Work towards it step by step with your tools. When it is done, call `complete_task` with " +
          (if taskType.hasResultShape then "the result, in the shape its schema describes."
           else """{"result": "<your answer>"}.""") +
          " If it cannot be done, call `fail_task` with the reason."
      ),
      Some(s"This is iteration $n of $budget.$warning")
    ).flatten.mkString("\n\n")

  private def firstTurn(
      taskRecord: TaskRecord,
      dependencies: Vector[DependencyResult]
  ): ChatMessage =
    val attachments = taskRecord.attachments.map { a =>
      a.content match
        case AttachmentContent.Inline(text) => s"Attachment '${a.name}' (${a.contentType}):\n$text"
        case AttachmentContent.Reference(uri) =>
          s"Attachment '${a.name}' (${a.contentType}) is available at $uri"
    }
    val results = dependencies.map(d =>
      s"Result of task '${d.taskId}' (${d.typeName}), which this task depends on:\n${d.result}"
    )
    ChatMessage.User.text(
      (Vector(taskRecord.instructions) ++ attachments ++ results).mkString("\n\n")
    )

  private def builtIns(taskType: TaskType[?]): Vector[ToolSpec] =
    Vector(
      ToolSpec(
        AutonomousAgent.CompleteTask,
        s"Completes the task with its result. Call this once the task is done.",
        taskType.completionSchema
      ),
      ToolSpec(
        AutonomousAgent.FailTask,
        "Gives up on the task, saying why. Call this only when the task cannot be done.",
        Json.obj(
          "type"                 -> Json.str("object"),
          "properties"           -> Json.obj("reason" -> Json.obj("type" -> Json.str("string"))),
          "required"             -> Json.arr(Json.str("reason")),
          "additionalProperties" -> Json.bool(false)
        )
      )
    )

  /**
   * Checks a task's instructions against the input guardrails, before any model call.
   *
   * Three answers, not two: a judged guardrail whose provider is down has refused nothing, and the
   * task should not fail for it — the host treats that as a failed iteration and tries again.
   */
  def startCheck(taskRecord: TaskRecord): StartCheck =
    try
      checkGuardrails(taskRecord.instructions, Guardrails.Direction.Input) match
        case Some(reason) => StartCheck.Refused(reason)
        case None         => StartCheck.Allowed
    catch
      case failed: Guardrails.GuardrailCheckFailed => StartCheck.CouldNotCheck(failed.getMessage)
      // An exhausted script fails the task at once, as an exhausted model script does.
      case failure: JudgmentScriptFailed => StartCheck.Refused(failure.getMessage)

  private def checkGuardrails(text: String, direction: Guardrails.Direction): Option[String] =
    Guardrails
      .check(definition.guardrails, text, direction, judgments, Guardrails.Spent())
      .map(_.message)

private[ankka] object IterationLoop:

  val BuiltIns: Set[String] = Set(AutonomousAgent.CompleteTask, AutonomousAgent.FailTask)

  /** A task's working history is the session with this id. */
  def sessionIdFor(taskId: String): SessionId = SessionId(s"task:$taskId")

  final case class DependencyResult(taskId: String, typeName: String, result: String)

  enum ResumePoint:
    /** Start iteration n: nothing of it is recorded yet. */
    case NextIteration(n: Int)

    /** Iteration n started and its model call did not land: call the model again, for n. */
    case CallModel(n: Int)

    /** Iteration n's response landed but its completion was not recorded. */
    case RecordCompletion(n: Int)

    /** Iteration n completed and its tools did not all land: run them again. */
    case RunTools(n: Int, response: SessionMessage.AiMessage)

  enum StartCheck:
    case Allowed
    case Refused(reason: String)
    case CouldNotCheck(error: String)

  enum IterationResult:
    /** The iteration ended with nothing decided; the next one follows. */
    case Continue

    /**
     * The iteration failed — its model call, or a check of the result it proposed — and is tried
     * again, as the same iteration, after a pause.
     */
    case Faulted(error: String)

    /** The model completed the task and its result passed every rule. */
    case Completed(result: String)

    /** The model completed the task with a result a rule or guardrail refused. */
    case Rejected(reason: String)

    /** The task ended without a result. */
    case Ended(outcome: TaskOutcome)
