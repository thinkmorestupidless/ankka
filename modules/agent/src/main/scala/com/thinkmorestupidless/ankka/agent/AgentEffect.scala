package com.thinkmorestupidless.ankka.agent

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

/** How an agent's reply is shaped. */
sealed trait ResponseShape[R]:
  def decode(text: String): Either[String, R]

object ResponseShape:

  case object AsText extends ResponseShape[String]:
    def decode(text: String): Either[String, String] = Right(text)

  /**
   * A structured reply, parsed from the model's JSON.
   *
   * The parse failure is surfaced rather than swallowed: a model that returned prose where JSON was
   * asked for is a prompt problem, and silently returning a default would hide it.
   */
  final class AsJson[R](codec: JsonValueCodec[R], val schemaHint: String) extends ResponseShape[R]:
    def decode(text: String): Either[String, R] =
      try Right(readFromString(text)(using codec))
      catch
        case failure: Throwable =>
          Left(s"the model's reply was not valid ${schemaHint}: ${failure.getMessage}")

/**
 * A description of one interaction with a model.
 *
 * Inert, like every other ankka effect: building it calls no model and reads no memory. That is
 * what lets an agent's decision-making be unit-tested against a scripted provider with nothing
 * running.
 *
 * A plain class rather than a `case class`, and its fields are named differently from its methods
 * on purpose. A field called `tools` would shadow the `tools(...)` builder — `effect.tools(x)`
 * would silently resolve to `Vector.apply(index)` and fail with a type error about `Int`.
 */
final class AgentEffect[R] private[agent] (
    private[agent] val chosenModel: Option[ModelProvider],
    private[agent] val system: Option[String],
    private[agent] val user: Option[String],
    private[agent] val context: Vector[String],
    private[agent] val memoryProvider: MemoryProvider,
    private[agent] val functionTools: Vector[FunctionTool],
    private[agent] val responseShape: ResponseShape[R],
    private[agent] val guards: Vector[Guardrail],
    private[agent] val failure: Option[CommandError]
):

  private def with_[R2](
      chosenModel: Option[ModelProvider] = chosenModel,
      system: Option[String] = system,
      user: Option[String] = user,
      context: Vector[String] = context,
      memoryProvider: MemoryProvider = memoryProvider,
      functionTools: Vector[FunctionTool] = functionTools,
      responseShape: ResponseShape[R2] = responseShape.asInstanceOf[ResponseShape[R2]],
      guards: Vector[Guardrail] = guards,
      failure: Option[CommandError] = failure
  ): AgentEffect[R2] =
    new AgentEffect[R2](
      chosenModel,
      system,
      user,
      context,
      memoryProvider,
      functionTools,
      responseShape,
      guards,
      failure
    )

  /** Overrides the model for this interaction. */
  def model(provider: ModelProvider): AgentEffect[R] = with_[R](chosenModel = Some(provider))

  /** Sets the instruction that frames the whole conversation. */
  def systemMessage(text: String): AgentEffect[R] = with_[R](system = Some(text))

  def userMessage(text: String): AgentEffect[R] = with_[R](user = Some(text))

  /**
   * Adds context the user did not type — retrieved documents, an entity's state.
   *
   * Kept separate from `userMessage` so memory records what the user actually said rather than the
   * whole assembled prompt.
   */
  def withContext(text: String): AgentEffect[R] = with_[R](context = context :+ text)

  def memory(provider: MemoryProvider): AgentEffect[R] = with_[R](memoryProvider = provider)

  def tools(tools: FunctionTool*): AgentEffect[R] =
    with_[R](functionTools = functionTools ++ tools)

  def guardrails(guardrails: Guardrail*): AgentEffect[R] = with_[R](guards = guards ++ guardrails)

  /** Replies with the model's text. */
  def thenReply(): AgentEffect[String] = with_[String](responseShape = ResponseShape.AsText)

  /**
   * Streams the reply as it is generated.
   *
   * Every turn streams, not just the last one. A model often says "let me check the weather" before
   * calling a tool, and that preamble is real output a reader should see — withholding it until the
   * tool round-trip finishes is what makes a streaming UI feel broken.
   */
  def thenStream(): AgentStreamEffect =
    AgentStreamEffect(with_[String](responseShape = ResponseShape.AsText))

  /**
   * Replies with the model's JSON, parsed into `T`.
   *
   * The schema is not sent to the model — say what you want in the system message. This only
   * decodes, so a mismatch surfaces as a decode error naming the type.
   */
  def thenReplyAs[T](using codec: JsonValueCodec[T]): AgentEffect[T] =
    with_[T](responseShape = ResponseShape.AsJson(codec, "JSON"))

/** The `effects` surface inside an agent's command handler. */
final class AgentEffects private[agent] (defaultModel: () => Option[ModelProvider]):

  /** Starts describing an interaction. */
  private def blank[R](shape: ResponseShape[R], failure: Option[CommandError] = None) =
    new AgentEffect[R](
      defaultModel(),
      None,
      None,
      Vector.empty,
      MemoryProvider.limitedWindow,
      Vector.empty,
      shape,
      Vector.empty,
      failure
    )

  /** Overrides the model for this interaction. */
  def model(provider: ModelProvider): AgentEffect[String] =
    blank(ResponseShape.AsText).model(provider)

  /** Sets the instruction that frames the whole conversation. */
  def systemMessage(text: String): AgentEffect[String] =
    blank(ResponseShape.AsText).systemMessage(text)

  def userMessage(text: String): AgentEffect[String] =
    blank(ResponseShape.AsText).userMessage(text)

  /** Rejects the request without calling a model. */
  def error[R](message: String): AgentEffect[R] =
    blank[R](ResponseShape.AsText.asInstanceOf[ResponseShape[R]], Some(CommandError(message)))

  def error[R](message: String, code: ErrorCode): AgentEffect[R] =
    blank[R](
      ResponseShape.AsText.asInstanceOf[ResponseShape[R]],
      Some(CommandError(message, code))
    )

/**
 * A check applied to what goes into, or comes out of, a model.
 *
 * Returning `Left` rejects the interaction. Input guardrails run before any model is called, so a
 * rejection there costs nothing; output guardrails run before memory is written, so a rejected
 * reply leaves no trace in the conversation.
 *
 * Guardrails run in declaration order and stop at the first rejection — put the cheap checks first.
 */
trait Guardrail:
  def name: String

  // Default to allowing, so a guardrail overrides only the direction it cares about.
  def checkInput(text: String): Either[String, Unit] =
    val _ = text
    Right(())

  def checkOutput(text: String): Either[String, Unit] =
    val _ = text
    Right(())

object Guardrail:

  /** Rejects input longer than `maxChars`, before it costs a model call. */
  def maxInputLength(maxChars: Int): Guardrail = new Guardrail:
    val name = s"max-input-length($maxChars)"
    override def checkInput(text: String): Either[String, Unit] =
      if text.length <= maxChars then Right(())
      else Left(s"input is ${text.length} characters, over the $maxChars limit")

  /** Rejects input or output matching `pattern`. */
  def forbidding(guardName: String, pattern: scala.util.matching.Regex): Guardrail =
    new Guardrail:
      val name = guardName
      override def checkInput(text: String): Either[String, Unit] =
        if pattern.findFirstIn(text).isEmpty then Right(())
        else Left(s"input rejected by $guardName")
      override def checkOutput(text: String): Either[String, Unit] =
        if pattern.findFirstIn(text).isEmpty then Right(())
        else Left(s"output rejected by $guardName")

/**
 * A description of a streaming interaction.
 *
 * A distinct type from `AgentEffect` so that `Agent.Companion.stream` accepts only handlers that
 * actually stream — a streaming call site cannot be pointed at a handler that returns one value, or
 * the reverse.
 */
final class AgentStreamEffect private[agent] (private[agent] val effect: AgentEffect[String])
