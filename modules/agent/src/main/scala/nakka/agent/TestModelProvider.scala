package nakka.agent

import java.util.concurrent.{ConcurrentLinkedQueue, CopyOnWriteArrayList}
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * A model that answers from a script.
 *
 * Agent tests should be deterministic and free. Everything interesting about an agent —
 * whether it calls the right tool, whether it respects memory, what it does with a
 * refusal — is decided by nakka, not by the model, so a scripted model exercises all of
 * it without a network or an API key.
 *
 * {{{
 * val model = TestModelProvider()
 *   .expectToolCall("get_weather", Json.obj("location" -> Json.str("Berlin")))
 *   .expectText("It is sunny in Berlin.")
 * }}}
 */
final class TestModelProvider(val modelName: String = "test-model") extends ModelProvider:

  private val scripted = ConcurrentLinkedQueue[ModelResponse]()
  private val rules    = CopyOnWriteArrayList[(ModelRequest => Boolean, ModelResponse)]()
  private val seen     = ConcurrentLinkedQueue[ModelRequest]()

  def name: String = "test"

  /** Queues a plain text reply. Scripted replies are consumed in order. */
  def expectText(text: String): TestModelProvider =
    scripted.add(ModelResponse(text)): Unit
    this

  /** Queues a reply asking for one tool call. */
  def expectToolCall(
      toolName: String,
      arguments: Json,
      callId: String = "test-call"
  ): TestModelProvider =
    scripted.add(
      ModelResponse(
        text = "",
        toolCalls = Vector(ToolCall(callId, toolName, arguments)),
        stopReason = StopReason.ToolUse
      )
    ): Unit
    this

  /** Queues a reply asking for several tool calls at once. */
  def expectParallelToolCalls(calls: ToolCall*): TestModelProvider =
    scripted.add(
      ModelResponse(text = "", toolCalls = calls.toVector, stopReason = StopReason.ToolUse)
    ): Unit
    this

  /** Queues a refusal — which arrives as a successful response, not an exception. */
  def expectRefusal(reason: String): TestModelProvider =
    scripted.add(
      ModelResponse("", Vector.empty, StopReason.Refusal, TokenUsage.zero, Some(reason))
    ): Unit
    this

  def expect(response: ModelResponse): TestModelProvider =
    scripted.add(response): Unit
    this

  /** A standing rule, used once the script runs out. */
  def whenRequest(matches: ModelRequest => Boolean)(response: ModelResponse): TestModelProvider =
    rules.add(matches -> response): Unit
    this

  /** A standing rule keyed on the latest user message. */
  def whenUserSays(substring: String)(reply: String): TestModelProvider =
    whenRequest(request => latestUserText(request).exists(_.contains(substring)))(
      ModelResponse(reply)
    )

  /** Every request the agent made, in order — the main thing tests assert on. */
  def requests: Seq[ModelRequest] = seen.asScala.toSeq

  def lastRequest: ModelRequest =
    requests.lastOption.getOrElse(throw AssertionError("the model was never called"))

  def callCount: Int = seen.size

  def reset(): Unit =
    scripted.clear()
    rules.clear()
    seen.clear()

  def complete(request: ModelRequest): Future[ModelResponse] =
    seen.add(request): Unit
    Option(scripted.poll()) match
      case Some(response) => Future.successful(response)
      case None =>
        rules.asScala.collectFirst { case (matches, response) if matches(request) => response } match
          case Some(response) => Future.successful(response)
          case None =>
            // Failing loudly beats returning a plausible-looking default: a test whose
            // model ran out of script is a test that is no longer testing what it says.
            Future.failed(
              ModelCallFailed(
                name,
                s"no scripted response left for request with " +
                  s"${request.messages.size} message(s) and ${request.tools.size} tool(s). " +
                  "Add expectText/expectToolCall, or a whenUserSays rule."
              )
            )

  private def latestUserText(request: ModelRequest): Option[String] =
    request.messages.reverseIterator
      .collectFirst { case user: ChatMessage.User => user }
      .map(_.content.collect { case MessageContent.Text(text) => text }.mkString("\n"))

object TestModelProvider:
  def apply(): TestModelProvider = new TestModelProvider()

/** Convenience constructors, mostly for tests. */
extension (response: ModelResponse.type)
  def text(value: String): ModelResponse = ModelResponse(value)
