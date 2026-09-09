package nakka.agent

import nakka.core.*
import nakka.sdk.ComponentClient

import scala.concurrent.duration.FiniteDuration
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.scaladsl.Sink

import scala.concurrent.Await
import scala.util.control.NonFatal

/**
 * Carries out one interaction: memory in, model call, tools, memory out.
 *
 * Written as straight-line blocking code and run on a virtual thread. That is the whole point of
 * the virtual-thread decision — a tool loop is inherently sequential (call the model, run what it
 * asked for, call it again), and expressing it as a chain of futures would obscure the one thing a
 * reader needs to follow.
 */
private[agent] final class AgentLoop(
    descriptor: AgentDescriptor[Agent],
    sessionId: SessionId,
    componentClient: ComponentClient,
    modelTimeout: FiniteDuration
):

  private val agentId = descriptor.componentId

  /** Runs the interaction, returning either a rejection or the decoded reply. */
  def run[R](effect: AgentEffect[R]): Either[CommandError, R] =
    effect.failure match
      case Some(rejection) => Left(rejection)
      case None            => execute(effect)

  private def execute[R](effect: AgentEffect[R]): Either[CommandError, R] =
    val userText = effect.user.getOrElse("")

    for
      provider <- effect.chosenModel.toRight(
        CommandError(
          s"agent '$agentId' has no model: pass one with effects.model(...), or " +
            "configure a default provider on the AgentRuntime",
          ErrorCode.Internal
        )
      )
      // Input guardrails run before anything is spent.
      _ <- checkGuardrails(effect.guards, userText, input = true)

      history =
        if effect.memoryProvider.read then readHistory(effect.memoryProvider) else Vector.empty
      prompt = buildPrompt(effect, history, userText)

      outcome <- runToolLoop(provider, effect, prompt)
      _       <- checkGuardrails(effect.guards, outcome.response.text, input = false)

      decoded <- effect.responseShape
        .decode(outcome.response.text)
        .left
        .map(CommandError(_, ErrorCode.Internal))
    yield
      // Memory is written only once the reply has survived guardrails and decoding, so a
      // rejected interaction leaves no trace in the conversation.
      if effect.memoryProvider.write then writeHistory(effect, userText, outcome)
      decoded

  /**
   * Runs the interaction, pushing text to `emit` as it is generated.
   *
   * The same loop as `run`, differing only in that each turn is streamed. Tool rounds stream too —
   * a model's "let me look that up" preamble is output worth showing.
   *
   * `emit` may block (it does, to apply backpressure), which is fine: this runs on a virtual
   * thread.
   */
  def runStreaming(
      effect: AgentStreamEffect,
      emit: String => Unit
  )(using system: ActorSystem[?]): Either[CommandError, Unit] =
    val described = effect.effect
    described.failure match
      case Some(rejection) => Left(rejection)
      case None            => executeStreaming(described, emit)

  private def executeStreaming(
      effect: AgentEffect[String],
      emit: String => Unit
  )(using system: ActorSystem[?]): Either[CommandError, Unit] =
    val userText = effect.user.getOrElse("")

    for
      provider <- effect.chosenModel.toRight(
        CommandError(
          s"agent '$agentId' has no model: pass one with effects.model(...), or " +
            "configure a default provider on the AgentRuntime",
          ErrorCode.Internal
        )
      )
      _ <- checkGuardrails(effect.guards, userText, input = true)

      history =
        if effect.memoryProvider.read then readHistory(effect.memoryProvider) else Vector.empty
      prompt = buildPrompt(effect, history, userText)

      outcome <- streamToolLoop(provider, effect, prompt, emit)

      // Output guardrails run after the fact when streaming: tokens have already been
      // delivered, so a rejection here stops memory being written but cannot un-send
      // what the reader saw. Use input guardrails for anything that must never be shown.
      _ <- checkGuardrails(effect.guards, outcome.response.text, input = false)
    yield if effect.memoryProvider.write then writeHistory(effect, userText, outcome)

  private def streamToolLoop(
      provider: ModelProvider,
      effect: AgentEffect[?],
      prompt: Vector[ChatMessage],
      emit: String => Unit
  )(using system: ActorSystem[?]): Either[CommandError, Outcome] =
    val tools    = effect.functionTools.map(tool => tool.name -> tool).toMap
    var messages = prompt
    var produced = Vector.empty[ChatMessage]
    var usage    = TokenUsage.zero
    var steps    = 0

    while true do
      val request = ModelRequest(
        settings = ModelSettings(provider.modelName),
        systemMessage = effect.system,
        messages = messages,
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

      val response = completed.get
      usage = usage + response.usage

      if response.isRefusal then
        return Left(
          CommandError(
            response.refusalReason.getOrElse(s"${provider.name} declined the request"),
            ErrorCode.Forbidden
          )
        )

      if !response.wantsTools then
        return Right(Outcome(response, produced :+ ChatMessage.Assistant(response.text), usage))

      steps += 1
      if steps > descriptor.maxToolCallSteps then
        return Left(
          CommandError(
            s"agent '$agentId' exceeded ${descriptor.maxToolCallSteps} tool-call steps " +
              "without producing an answer",
            ErrorCode.Internal
          )
        )

      val results = response.toolCalls.map(call => runTool(tools, call))
      val newTurns = Vector(
        ChatMessage.Assistant(response.text, response.toolCalls),
        ChatMessage.ToolResults(results)
      )
      messages = messages ++ newTurns
      produced = produced ++ newTurns
    end while

    Left(CommandError("unreachable", ErrorCode.Internal))

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

  /** Turns stored history back into provider-shaped turns. */
  private def replay(history: Vector[SessionMessage]): Vector[ChatMessage] =
    history.foldLeft(Vector.empty[ChatMessage]) { (turns, message) =>
      message match
        case m: SessionMessage.UserMessage =>
          turns :+ ChatMessage.User.text(m.text)

        case m: SessionMessage.SummaryMessage =>
          // A summary stands in for the turns it replaced, as context rather than as
          // something the user said.
          turns :+ ChatMessage.User.text(s"[earlier conversation, summarised] ${m.text}")

        case m: SessionMessage.AiMessage =>
          turns :+ ChatMessage.Assistant(
            m.text,
            m.toolCalls.map(call =>
              ToolCall(
                call.id,
                call.name,
                Json.parse(call.arguments).getOrElse(Json.Obj(Map.empty))
              )
            )
          )

        case m: SessionMessage.ToolResultMessage =>
          val result = ToolResult(m.callId, m.toolName, m.content, m.isError)
          // Coalesce consecutive results into the single message the providers expect.
          turns.lastOption match
            case Some(ChatMessage.ToolResults(existing)) =>
              turns.init :+ ChatMessage.ToolResults(existing :+ result)
            case _ =>
              turns :+ ChatMessage.ToolResults(Vector(result))
    }

  // ── The loop ──────────────────────────────────────────────────────────────

  /**
   * The result of one interaction.
   *
   * `produced` holds only the turns *this* interaction created. Replayed history is already in the
   * journal, and recording it again would double the conversation on every request.
   */
  private final case class Outcome(
      response: ModelResponse,
      produced: Vector[ChatMessage],
      usage: TokenUsage
  )

  private def runToolLoop(
      provider: ModelProvider,
      effect: AgentEffect[?],
      prompt: Vector[ChatMessage]
  ): Either[CommandError, Outcome] =
    val tools    = effect.functionTools.map(tool => tool.name -> tool).toMap
    var messages = prompt
    var produced = Vector.empty[ChatMessage]
    var usage    = TokenUsage.zero
    var steps    = 0

    while true do
      val request = ModelRequest(
        settings = ModelSettings(provider.modelName),
        systemMessage = effect.system,
        messages = messages,
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

      usage = usage + response.usage

      if response.isRefusal then
        return Left(
          CommandError(
            response.refusalReason.getOrElse(s"${provider.name} declined the request"),
            ErrorCode.Forbidden
          )
        )

      if !response.wantsTools then
        return Right(Outcome(response, produced :+ ChatMessage.Assistant(response.text), usage))

      steps += 1
      if steps > descriptor.maxToolCallSteps then
        // A bound, not a suggestion. A model that keeps asking for tools without
        // converging would otherwise spend without limit.
        return Left(
          CommandError(
            s"agent '$agentId' exceeded ${descriptor.maxToolCallSteps} tool-call steps " +
              "without producing an answer",
            ErrorCode.Internal
          )
        )

      val results = response.toolCalls.map(call => runTool(tools, call))
      val newTurns = Vector(
        ChatMessage.Assistant(response.text, response.toolCalls),
        ChatMessage.ToolResults(results)
      )

      messages = messages ++ newTurns
      produced = produced ++ newTurns
    end while

    Left(CommandError("unreachable", ErrorCode.Internal))

  /**
   * Runs one tool call.
   *
   * A tool that fails comes back as a tool result flagged as an error rather than as an exception,
   * because that is what lets the model recover — usually by fixing its arguments and trying again.
   * Failing the whole request would deny it the chance.
   */
  private def runTool(tools: Map[String, FunctionTool], call: ToolCall): ToolResult =
    tools.get(call.name) match
      case None =>
        ToolResult(
          call.id,
          call.name,
          s"no tool named '${call.name}' is available; available tools: " +
            tools.keys.toVector.sorted.mkString(", "),
          isError = true
        )
      case Some(tool) =>
        tool.invoke(call.arguments) match
          case Right(content) => ToolResult(call.id, call.name, content)
          case Left(problem)  => ToolResult(call.id, call.name, problem, isError = true)

  // ── Guardrails ────────────────────────────────────────────────────────────

  private def checkGuardrails(
      guardrails: Vector[Guardrail],
      text: String,
      input: Boolean
  ): Either[CommandError, Unit] =
    guardrails.iterator
      .map(guard =>
        guard.name -> (if input then guard.checkInput(text) else guard.checkOutput(text))
      )
      .collectFirst { case (name, Left(reason)) =>
        CommandError(s"guardrail '$name': $reason", ErrorCode.Forbidden)
      }
      .toLeft(())

  // ── Memory ────────────────────────────────────────────────────────────────

  private def memoryEntity =
    componentClient.forEventSourcedEntity(EntityId(sessionId))

  private def readHistory(memory: MemoryProvider): Vector[SessionMessage] =
    val stored   = memoryEntity.call(SessionMemoryEntity.history).invoke()
    val filtered = stored.messages.filter(memory.filter.matches)
    memory.readLast match
      case Some(count) => filtered.takeRight(count)
      case None        => filtered

  private def writeHistory[R](
      effect: AgentEffect[R],
      userText: String,
      outcome: Outcome
  ): Unit =
    val now  = System.currentTimeMillis()
    val role = descriptor.role

    val recorded = Vector.newBuilder[SessionMessage]

    if userText.nonEmpty then recorded += SessionMessage.UserMessage(now, userText, role)

    outcome.produced.foreach {
      case ChatMessage.Assistant(text, calls) =>
        recorded += SessionMessage.AiMessage(
          now,
          text,
          role,
          calls.map(call => RecordedToolCall(call.id, call.name, call.arguments.render))
        )

      case ChatMessage.ToolResults(results) =>
        results.foreach { result =>
          recorded += SessionMessage.ToolResultMessage(
            now,
            result.callId,
            result.name,
            result.content,
            result.isError,
            role
          )
        }

      case _: ChatMessage.User => () // the user turn is recorded above, unassembled
    }

    val messages = effect.memoryProvider.interceptor match
      case Some(interceptor) => recorded.result().map(interceptor.beforeWrite(sessionId, _))
      case None              => recorded.result()

    if messages.nonEmpty then
      memoryEntity
        .call(SessionMemoryEntity.append)
        .invoke(SessionMemoryEntity.Append(messages, outcome.usage)): Unit
