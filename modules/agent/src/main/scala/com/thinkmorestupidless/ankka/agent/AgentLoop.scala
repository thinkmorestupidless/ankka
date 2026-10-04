package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.agent.judgment.{
  JudgmentFailed,
  JudgmentScriptFailed,
  Judgments,
  NoJudgmentProvider
}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import scala.concurrent.duration.FiniteDuration
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.util.control.NonFatal

/**
 * Which handler built a turn's effect, and from what request.
 *
 * Recorded with a turn that waits for approval, because the effect itself holds closures and cannot
 * be: going on means running the handler again on the same request, which is safe because building
 * an effect performs no I/O and calls no model.
 */
private[agent] final case class TurnOrigin(
    handler: String,
    streaming: Boolean,
    payload: Array[Byte]
)

/**
 * Carries out one interaction: memory in, model call, tools, memory out.
 *
 * Written as straight-line blocking code and run on a virtual thread. That is the whole point of
 * the virtual-thread decision — a tool loop is inherently sequential (call the model, run what it
 * asked for, call it again), and expressing it as a chain of futures would obscure the one thing a
 * reader needs to follow.
 *
 * A turn can stop before it answers: when the model calls a tool that requires approval, the loop
 * records the turn in the session as suspended and answers with the approval requests. A decision
 * goes on from there through `resume`.
 */
private[agent] final class AgentLoop(
    descriptor: AgentDescriptor[Agent],
    sessionId: SessionId,
    componentClient: ComponentClient,
    modelTimeout: FiniteDuration,
    judgments: Judgments
):

  private val agentId = descriptor.componentId
  private val log     = LoggerFactory.getLogger(getClass)

  /** Runs the interaction, returning a rejection, the decoded reply, or the requests it awaits. */
  def run[R](effect: AgentEffect[R], origin: TurnOrigin): Either[CommandError, AgentOutcome[R]] =
    (effect.failure, effect.judgmentPlan) match
      case (Some(rejection), _) => Left(rejection)
      case (None, Some(plan))   => runJudgment(plan).map(AgentOutcome.Answered(_))
      case (None, None)         => execute(effect, origin)

  /**
   * Asks the plan's questions and replies from the answers.
   *
   * Nothing is read from the session and nothing is written to it: a judgment is asked of the state
   * the handler gave it and nothing else, and it is not a turn in the conversation.
   */
  private def runJudgment[R](plan: JudgmentPlan[R]): Either[CommandError, R] =
    try
      val judgment = judgments.ask(plan.provider, plan.state, plan.questions)
      recordJudgmentUsage(judgment.usage)
      Right(plan.reply(judgment))
    catch
      case _: NoJudgmentProvider =>
        Left(
          CommandError(
            s"agent '$agentId' has no judgment provider: pass one with " +
              "effects.judgment.provider(...), or configure one with withJudgments(...) on the " +
              "AgentRuntime",
            ErrorCode.Internal
          )
        )
      case failure: JudgmentFailed =>
        Left(
          CommandError(
            failure.getMessage,
            if failure.timedOut then ErrorCode.Timeout else ErrorCode.Unavailable
          )
        )
      case failure: JudgmentScriptFailed =>
        Left(CommandError(failure.getMessage, ErrorCode.Internal))

  private def execute[R](
      effect: AgentEffect[R],
      origin: TurnOrigin
  ): Either[CommandError, AgentOutcome[R]] =
    val userText = effect.user.getOrElse("")
    val spent    = Guardrails.Spent()

    val result = for
      provider <- chosenModel(effect)
      // Admission first: a session waiting on a decision takes no new turn, whatever it would
      // have cost to find that out after the guardrails ran.
      state <- admit()
      // Input guardrails run before anything is spent on the model.
      _ <- checkGuardrails(effect.guards, userText, Guardrails.Direction.Input, spent)

      history =
        if effect.memoryProvider.read then visible(state, effect.memoryProvider) else Vector.empty
      prompt = buildPrompt(effect, history, userText)

      ended   <- runToolLoop(provider, effect, Progress.start(prompt))
      outcome <- finish(effect, userText, ended, spent, origin, resumed = false)
    yield outcome
    // A refused or failed interaction writes no message, but what its guardrails spent was spent.
    result.left.foreach(_ => recordJudgmentUsage(spent.usage))
    result

  /**
   * Goes on with a suspended turn once every one of its approval requests is decided.
   *
   * `effect` is what the turn's handler builds again from the recorded request. The approved calls
   * run now, in the order the model made them, and a refused or expired one is answered to the
   * model as refused; the loop then carries on from where it stopped, and may answer, fail, or stop
   * again for another approval. Input guardrails are not run again: they passed when the turn
   * began. However it ends, the suspended turn is over — a failure ends it with nothing added to
   * the history, so the approved tools are run at most once.
   */
  def resume[R](
      effect: AgentEffect[R],
      turn: SuspendedTurn
  ): Either[CommandError, AgentOutcome[R]] =
    val userText = effect.user.getOrElse("")
    val spent    = Guardrails.Spent()
    spent.usage = turn.judgmentUsage
    val origin = TurnOrigin(turn.handler, turn.streaming, turn.payloadBytes)

    val result = for
      provider <- effect.failure.toLeft(()).flatMap(_ => chosenModel(effect))
      state = memoryEntity.call(SessionMemoryEntity.history).invoke()
      history =
        if effect.memoryProvider.read then visible(state, effect.memoryProvider) else Vector.empty
      progress = settle(effect, turn, buildPrompt(effect, history, userText))
      ended   <- runToolLoop(provider, effect, progress)
      outcome <- finish(effect, userText, ended, spent, origin, resumed = true)
    yield outcome
    result.left.foreach { _ =>
      endTurn()
      recordJudgmentUsage(spent.usage)
    }
    result

  /**
   * Runs the interaction, pushing text to `emit` as it is generated.
   *
   * The same loop as `run`, differing only in that each turn is streamed. Tool rounds stream too —
   * a model's "let me look that up" preamble is output worth showing. When the turn stops for
   * approval the requests are returned, and the caller ends the stream with them.
   *
   * `emit` may block (it does, to apply backpressure), which is fine: this runs on a virtual
   * thread.
   */
  def runStreaming(
      effect: AgentStreamEffect,
      emit: String => Unit,
      origin: TurnOrigin
  )(using system: ActorSystem[?]): Either[CommandError, Option[Vector[ApprovalRequest]]] =
    val described = effect.effect
    described.failure match
      case Some(rejection) => Left(rejection)
      case None            => executeStreaming(described, emit, origin)

  private def executeStreaming(
      effect: AgentEffect[String],
      emit: String => Unit,
      origin: TurnOrigin
  )(using system: ActorSystem[?]): Either[CommandError, Option[Vector[ApprovalRequest]]] =
    val userText = effect.user.getOrElse("")
    val spent    = Guardrails.Spent()

    val result = for
      provider <- chosenModel(effect)
      state    <- admit()
      _        <- checkGuardrails(effect.guards, userText, Guardrails.Direction.Input, spent)

      history =
        if effect.memoryProvider.read then visible(state, effect.memoryProvider) else Vector.empty
      prompt = buildPrompt(effect, history, userText)

      ended <- streamToolLoop(provider, effect, prompt, emit)

      // Output guardrails run after the fact when streaming: tokens have already been
      // delivered, so a rejection here stops memory being written but cannot un-send
      // what the reader saw. Use input guardrails for anything that must never be shown.
      outcome <- finish(effect, userText, ended, spent, origin, resumed = false)
    yield outcome match
      case AgentOutcome.Answered(_)                => None
      case AgentOutcome.AwaitingApproval(requests) => Some(requests)
    result.left.foreach(_ => recordJudgmentUsage(spent.usage))
    result

  // ── The end of a turn ─────────────────────────────────────────────────────

  /**
   * What a loop's end comes to. A turn that waits is recorded as suspended; one that answered
   * passes the output guardrails and the decode, and only then is it written to the history, so a
   * rejected interaction leaves no trace in the conversation.
   */
  private def finish[R](
      effect: AgentEffect[R],
      userText: String,
      ended: LoopEnd,
      spent: Guardrails.Spent,
      origin: TurnOrigin,
      resumed: Boolean
  ): Either[CommandError, AgentOutcome[R]] =
    ended match
      case LoopEnd.Wait(progress, requests) =>
        suspend(userText, progress, requests, spent, origin).map(_ =>
          AgentOutcome.AwaitingApproval(requests)
        )

      case LoopEnd.Answer(outcome) =>
        for
          _ <- checkGuardrails(
            effect.guards,
            outcome.response.text,
            Guardrails.Direction.Output,
            spent
          )
          decoded <- effect.responseShape
            .decode(outcome.response.text)
            .left
            .map(CommandError(_, ErrorCode.Internal))
        yield
          if effect.memoryProvider.write then
            writeHistory(effect, userText, outcome, spent.usage, endsTurn = resumed)
          else
            if resumed then endTurn()
            recordJudgmentUsage(spent.usage)
          AgentOutcome.Answered(decoded)

  private def suspend(
      userText: String,
      progress: Progress,
      requests: Vector[ApprovalRequest],
      spent: Guardrails.Spent,
      origin: TurnOrigin
  ): Either[CommandError, Unit] =
    val now = System.currentTimeMillis()
    val user =
      if userText.isEmpty then Vector.empty
      else Vector(SessionMessage.UserMessage(now, userText, descriptor.role))
    val turn = SuspendedTurn(
      agentId = agentId,
      handler = origin.handler,
      streaming = origin.streaming,
      payload = SuspendedTurn.encodePayload(origin.payload),
      messages = user ++ recorded(progress.produced, progress.decisions, now),
      usage = progress.usage,
      steps = progress.steps,
      requests = requests,
      judgmentUsage = spent.usage
    )
    try
      memoryEntity.call(SessionMemoryEntity.suspendTurn).invoke(turn): Unit
      Right(())
    catch case error: CommandError => Left(error)

  // ── Admission ─────────────────────────────────────────────────────────────

  /**
   * Reads the session and admits a new turn into it.
   *
   * A session with an approval request awaiting a decision takes no new turn: the person deciding
   * would otherwise answer a conversation that had moved on. A suspended turn with nothing awaiting
   * was cut off after its last decision — this host is not running it, since a session runs one
   * thing at a time — so it is ended first, with nothing added to the history.
   */
  private def admit(): Either[CommandError, SessionHistory] =
    val state = memoryEntity.call(SessionMemoryEntity.history).invoke()
    if state.awaiting.nonEmpty then
      Left(
        CommandError(
          s"session '$sessionId' has an approval request awaiting a decision: " +
            state.awaiting.map(r => s"'${r.tool}' (${r.id})").mkString(", "),
          ErrorCode.Conflict
        )
      )
    else if state.suspended.isDefined then
      endTurn()
      Right(state.copy(suspended = None))
    else Right(state)

  private def chosenModel(effect: AgentEffect[?]): Either[CommandError, ModelProvider] =
    effect.chosenModel.toRight(
      CommandError(
        s"agent '$agentId' has no model: pass one with effects.model(...), or " +
          "configure a default provider on the AgentRuntime",
        ErrorCode.Internal
      )
    )

  // ── Prompt assembly ───────────────────────────────────────────────────────

  private def buildPrompt(
      effect: AgentEffect[?],
      history: Vector[SessionMessage],
      userText: String
  ): Vector[ChatMessage] =
    val replayed = replay(history)

    // Extra context is appended to the user turn rather than sent as its own message:
    // retrieved documents belong with the question they answer, and a bare context
    // message with no question invites the model to summarise it instead.
    val userParts =
      if effect.context.isEmpty then userText
      else (userText +: effect.context).mkString("\n\n")

    if userParts.isEmpty then replayed
    else replayed :+ ChatMessage.User.text(userParts)

  private def replay(history: Vector[SessionMessage]): Vector[ChatMessage] =
    PromptReplay.replay(history)

  /**
   * Where a suspended turn goes on from: its recorded turns after the user's, with the results of
   * its decided calls joined to the results already made, in the order the model made the calls.
   */
  private def settle(
      effect: AgentEffect[?],
      turn: SuspendedTurn,
      prompt: Vector[ChatMessage]
  ): Progress =
    val tools = effect.functionTools.map(tool => tool.name -> tool).toMap
    val decided = turn.requests.flatMap { request =>
      request.decision.map { decision =>
        val result =
          if decision.approved then
            ToolRunner.run(tools, ToolCall(request.callId, request.tool, request.arguments))
          else ToolResult(request.callId, request.tool, refusal(decision), isError = true)
        result -> decision
      }
    }
    val rest = turn.messages.filterNot(_.isInstanceOf[SessionMessage.UserMessage])
    val earlierDecisions = rest.collect {
      case m: SessionMessage.ToolResultMessage if m.decision.isDefined =>
        m.callId -> m.decision.get
    }.toMap
    val replayed  = replay(rest)
    val callOrder = replayed.collect { case ChatMessage.Assistant(_, calls) => calls }.lastOption
    val order     = callOrder.getOrElse(Vector.empty).map(_.id).zipWithIndex.toMap
    def ordered(results: Vector[ToolResult]) =
      results.sortBy(r => order.getOrElse(r.callId, Int.MaxValue))

    val newResults = decided.map(_._1)
    val produced = replayed.lastOption match
      case Some(ChatMessage.ToolResults(existing)) =>
        replayed.init :+ ChatMessage.ToolResults(ordered(existing ++ newResults))
      case _ =>
        replayed :+ ChatMessage.ToolResults(ordered(newResults))

    Progress(
      messages = prompt ++ produced,
      produced = produced,
      usage = turn.usage,
      steps = turn.steps,
      decisions = earlierDecisions ++ decided.map((r, d) => r.callId -> d)
    )

  /** What the model is told for a call a person, or the platform, refused. */
  private def refusal(decision: Decision): String =
    if decision.expired then
      "The approval request for this tool call expired with no decision; the tool did not run."
    else
      s"A person (${decision.by}) refused this tool call; the tool did not run." +
        decision.note.map(note => s" Their note: $note").getOrElse("")

  // ── The loop ──────────────────────────────────────────────────────────────

  /**
   * The result of one interaction.
   *
   * `produced` holds only the turns *this* interaction created. Replayed history is already in the
   * journal, and recording it again would double the conversation on every request. `decisions` are
   * those the turn's approval-requiring calls followed, by call id.
   */
  private final case class Outcome(
      response: ModelResponse,
      produced: Vector[ChatMessage],
      usage: TokenUsage,
      decisions: Map[String, Decision]
  )

  /** A loop's state between model calls. */
  private final case class Progress(
      messages: Vector[ChatMessage],
      produced: Vector[ChatMessage],
      usage: TokenUsage,
      steps: Int,
      decisions: Map[String, Decision]
  )

  private object Progress:
    def start(prompt: Vector[ChatMessage]): Progress =
      Progress(prompt, Vector.empty, TokenUsage.zero, 0, Map.empty)

  private enum LoopEnd:
    case Answer(outcome: Outcome)
    case Wait(progress: Progress, requests: Vector[ApprovalRequest])

  private def runToolLoop(
      provider: ModelProvider,
      effect: AgentEffect[?],
      from: Progress
  ): Either[CommandError, LoopEnd] =
    val tools    = effect.functionTools.map(tool => tool.name -> tool).toMap
    var progress = from

    while true do
      val request = ModelRequest(
        settings = ModelSettings(provider.modelName),
        systemMessage = effect.system,
        messages = progress.messages,
        tools = effect.functionTools.map(_.spec)
      )

      val response =
        try Await.result(provider.complete(request), modelTimeout)
        catch
          case failure: ModelCallFailed =>
            return Left(CommandError(failure.getMessage, ErrorCode.Unavailable))
          case _: java.util.concurrent.TimeoutException =>
            return Left(
              CommandError(
                s"${provider.name} did not respond within $modelTimeout",
                ErrorCode.Timeout
              )
            )
          case NonFatal(failure) =>
            return Left(
              CommandError(
                s"${provider.name} call failed: ${Option(failure.getMessage).getOrElse(failure.toString)}",
                ErrorCode.Unavailable
              )
            )

      step(provider, tools, progress, response) match
        case Left(ended) => return ended
        case Right(next) => progress = next
    end while

    Left(CommandError("unreachable", ErrorCode.Internal))

  private def streamToolLoop(
      provider: ModelProvider,
      effect: AgentEffect[?],
      prompt: Vector[ChatMessage],
      emit: String => Unit
  )(using system: ActorSystem[?]): Either[CommandError, LoopEnd] =
    val tools    = effect.functionTools.map(tool => tool.name -> tool).toMap
    var progress = Progress.start(prompt)

    while true do
      val request = ModelRequest(
        settings = ModelSettings(provider.modelName),
        systemMessage = effect.system,
        messages = progress.messages,
        tools = effect.functionTools.map(_.spec)
      )

      val completed =
        try
          Await.result(
            provider
              .stream(request)
              .runWith(
                Sink.fold(Option.empty[ModelResponse]) { (last, chunk) =>
                  chunk match
                    case ModelChunk.TextDelta(text)     => emit(text); last
                    case ModelChunk.Completed(response) => Some(response)
                    case ModelChunk.Failed(error) => throw ModelCallFailed(provider.name, error)
                    case ModelChunk.ToolCallStarted(_) => last
                }
              ),
            modelTimeout
          )
        catch
          case failure: ModelCallFailed =>
            return Left(CommandError(failure.getMessage, ErrorCode.Unavailable))
          case _: java.util.concurrent.TimeoutException =>
            return Left(
              CommandError(
                s"${provider.name} did not respond within $modelTimeout",
                ErrorCode.Timeout
              )
            )
          case NonFatal(failure) =>
            return Left(
              CommandError(
                s"${provider.name} stream failed: ${Option(failure.getMessage).getOrElse(failure.toString)}",
                ErrorCode.Unavailable
              )
            )

      // A stream that ends without a terminal chunk is a provider bug, not an answer.
      if completed.isEmpty then
        return Left(
          CommandError(s"${provider.name} stream ended without completing", ErrorCode.Unavailable)
        )

      step(provider, tools, progress, completed.get) match
        case Left(ended) => return ended
        case Right(next) => progress = next
    end while

    Left(CommandError("unreachable", ErrorCode.Internal))

  /**
   * One model response, applied: an answer, a refusal, the step bound, or a round of tools.
   *
   * A tool that requires approval is not run. The others the model called in the same response are,
   * and the turn stops with one approval request per waiting call — the model made the calls
   * together, so their results go back to it together, once every request is decided.
   */
  private def step(
      provider: ModelProvider,
      tools: Map[String, FunctionTool],
      progress: Progress,
      response: ModelResponse
  ): Either[Either[CommandError, LoopEnd], Progress] =
    val usage = progress.usage + response.usage

    if response.isRefusal then
      Left(
        Left(
          CommandError(
            response.refusalReason.getOrElse(s"${provider.name} declined the request"),
            ErrorCode.Forbidden
          )
        )
      )
    else if !response.wantsTools then
      val produced = progress.produced :+ ChatMessage.Assistant(response.text)
      Left(Right(LoopEnd.Answer(Outcome(response, produced, usage, progress.decisions))))
    else if progress.steps + 1 > descriptor.maxToolCallSteps then
      // A bound, not a suggestion. A model that keeps asking for tools without
      // converging would otherwise spend without limit.
      Left(
        Left(
          CommandError(
            s"agent '$agentId' exceeded ${descriptor.maxToolCallSteps} tool-call steps " +
              "without producing an answer",
            ErrorCode.Internal
          )
        )
      )
    else
      val waiting =
        response.toolCalls.filter(call => tools.get(call.name).exists(_.approval.isDefined))
      val results = response.toolCalls.filterNot(waiting.contains).map(call => runTool(tools, call))
      val assistant = ChatMessage.Assistant(response.text, response.toolCalls)
      val steps     = progress.steps + 1

      if waiting.isEmpty then
        val newTurns = Vector(assistant, ChatMessage.ToolResults(results))
        Right(
          progress.copy(
            messages = progress.messages ++ newTurns,
            produced = progress.produced ++ newTurns,
            usage = usage,
            steps = steps
          )
        )
      else
        val now = System.currentTimeMillis()
        val requests = waiting.map { call =>
          ApprovalRequest(
            id = Approvals.newId(),
            callId = call.id,
            tool = call.name,
            arguments = call.arguments,
            requestedAt = now,
            expiresAt = tools(call.name).approval.flatMap(_.within).map(now + _.toMillis)
          )
        }
        val made =
          if results.isEmpty then Vector.empty else Vector(ChatMessage.ToolResults(results))
        val produced = progress.produced :+ assistant :++ made
        Left(
          Right(
            LoopEnd.Wait(progress.copy(produced = produced, usage = usage, steps = steps), requests)
          )
        )

  private def runTool(tools: Map[String, FunctionTool], call: ToolCall): ToolResult =
    ToolRunner.run(tools, call)

  // ── Guardrails ────────────────────────────────────────────────────────────

  private def checkGuardrails(
      guardrails: Vector[Guardrail],
      text: String,
      direction: Guardrails.Direction,
      spent: Guardrails.Spent
  ): Either[CommandError, Unit] =
    // A refusal is Forbidden; a check that could not be made is not a refusal, and says so.
    try
      Guardrails
        .check(guardrails, text, direction, judgments, spent)
        .map(refused => CommandError(refused.message, ErrorCode.Forbidden))
        .toLeft(())
    catch
      case failed: Guardrails.GuardrailCheckFailed => Left(failed.toCommandError)
      case failure: JudgmentScriptFailed =>
        Left(CommandError(failure.getMessage, ErrorCode.Internal))

  // ── Memory ────────────────────────────────────────────────────────────────

  private def memoryEntity =
    componentClient.forEventSourcedEntity(EntityId(sessionId))

  /** The part of the session's history this effect reads. */
  private def visible(state: SessionHistory, memory: MemoryProvider): Vector[SessionMessage] =
    val filtered = state.messages.filter(memory.filter.matches)
    memory.readLast match
      case Some(count) => filtered.takeRight(count)
      case None        => filtered

  /** A turn's provider-shaped messages in their recorded form. */
  private def recorded(
      produced: Vector[ChatMessage],
      decisions: Map[String, Decision],
      now: Long
  ): Vector[SessionMessage] =
    val role = descriptor.role
    produced.flatMap {
      case ChatMessage.Assistant(text, calls) =>
        Vector(
          SessionMessage.AiMessage(
            now,
            text,
            role,
            calls.map(call => RecordedToolCall(call.id, call.name, call.arguments.render))
          )
        )

      case ChatMessage.ToolResults(results) =>
        results.map { result =>
          SessionMessage.ToolResultMessage(
            now,
            result.callId,
            result.name,
            result.content,
            result.isError,
            role,
            decisions.get(result.callId)
          )
        }

      case _: ChatMessage.User => Vector.empty // the user turn is recorded apart, unassembled
    }

  private def writeHistory[R](
      effect: AgentEffect[R],
      userText: String,
      outcome: Outcome,
      judgmentUsage: TokenUsage,
      endsTurn: Boolean
  ): Unit =
    val now = System.currentTimeMillis()
    val user =
      if userText.isEmpty then Vector.empty
      else Vector(SessionMessage.UserMessage(now, userText, descriptor.role))
    val all = user ++ recorded(outcome.produced, outcome.decisions, now)

    val messages = effect.memoryProvider.interceptor match
      case Some(interceptor) => all.map(interceptor.beforeWrite(sessionId, _))
      case None              => all

    if messages.nonEmpty || endsTurn then
      memoryEntity
        .call(SessionMemoryEntity.append)
        .invoke(SessionMemoryEntity.Append(messages, outcome.usage, judgmentUsage, endsTurn)): Unit
    else recordJudgmentUsage(judgmentUsage)

  /** Ends a suspended turn. Best effort: the caller is already being told how the turn ended. */
  private def endTurn(): Unit =
    try memoryEntity.call(SessionMemoryEntity.endTurn).invoke(): Unit
    catch
      case NonFatal(failure) =>
        log.warn(
          s"agent '$agentId' could not end the suspended turn of session '$sessionId': " +
            Option(failure.getMessage).getOrElse(failure.toString)
        )

  /**
   * Records tokens spent on judgments when no message is written with them: a refused request, a
   * judgment handler, a reply that is not remembered.
   *
   * Best effort. The caller's outcome is already decided — a refusal must still arrive as a refusal
   * — so a failure to record is logged, never thrown.
   */
  private def recordJudgmentUsage(usage: TokenUsage): Unit =
    if usage != TokenUsage.zero then
      try
        memoryEntity
          .call(SessionMemoryEntity.append)
          .invoke(SessionMemoryEntity.Append(Vector.empty, TokenUsage.zero, usage)): Unit
      catch
        case NonFatal(failure) =>
          log.warn(
            s"agent '$agentId' could not record judgment tokens for session '$sessionId': " +
              Option(failure.getMessage).getOrElse(failure.toString)
          )
