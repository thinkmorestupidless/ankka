package nakka.agent

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.Future

/**
 * A model nakka can talk to.
 *
 * Deliberately narrow: two methods, and nakka's own request and response types. The
 * agent loop, tool dispatch, session memory, guardrails and token accounting all live
 * above this line, so adding a provider means writing one adapter rather than
 * re-implementing agent behaviour.
 */
trait ModelProvider:

  /** Identifies the provider in logs and errors. */
  def name: String

  /** The model this provider is configured to call. */
  def modelName: String

  def complete(request: ModelRequest): Future[ModelResponse]

  /**
   * Streams a response.
   *
   * The default implementation completes and emits the whole thing, so a provider only
   * overrides this if its API genuinely streams.
   */
  def stream(request: ModelRequest): Source[ModelChunk, NotUsed] =
    Source
      .future(complete(request))
      .mapConcat { response =>
        val text = if response.text.isEmpty then Vector.empty else Vector(ModelChunk.TextDelta(response.text))
        text ++ response.toolCalls.map(ModelChunk.ToolCallStarted(_)) ++
          Vector(ModelChunk.Completed(response))
      }

/** A model call that failed at the transport or provider level. */
final case class ModelCallFailed(provider: String, message: String, cause: Option[Throwable] = None)
    extends RuntimeException(s"$provider: $message", cause.orNull)
