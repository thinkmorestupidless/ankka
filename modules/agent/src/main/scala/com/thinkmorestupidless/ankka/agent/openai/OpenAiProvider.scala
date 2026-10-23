package com.thinkmorestupidless.ankka.agent.openai

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.{JsonValue, ObjectMappers}
import com.openai.errors.OpenAIServiceException
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.providers.{ModelProviders, Quirks, Shape, ToolCallIds}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.scaladsl.Source
import org.slf4j.LoggerFactory

import java.util.concurrent.Executors
import javax.net.ssl.{SSLSocketFactory, X509TrustManager}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/**
 * Talks to a model in OpenAI's protocol, through OpenAI's own Java SDK.
 *
 * The SDK is transport only, as Anthropic's is for `AnthropicProvider`: ankka owns the loop, tool
 * dispatch, memory and token accounting. A request is built as ankka's own JSON and handed to the
 * SDK whole, and an answer is read back as JSON, so the translation is ankka's and does not move
 * with the SDK's model classes, which change every working day.
 *
 * Two shapes. **Responses** is how OpenAI's own API is spoken: its current models call tools while
 * they reason only there. It is spoken without state on the vendor's side (`store: false`), and the
 * reasoning a model returns with a tool call is carried back to it within the turn as provider
 * state. **Chat Completions** is how every compatible endpoint is spoken, since it is what all of
 * them serve.
 */
final class OpenAiProvider private (
    connection: Either[String, OpenAIClient],
    val name: String,
    val modelName: String,
    val shape: Shape,
    defaults: OpenAiProvider.Defaults,
    quirks: Quirks
) extends ModelProvider:

  ModelProviders.validName(name).left.foreach(problem => throw IllegalArgumentException(problem))

  /** The SDK blocks, so calls run on virtual threads of their own, as Anthropic's do. */
  private given ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())

  private val settings = OpenAiSettings(defaults, quirks)

  def complete(request: ModelRequest): Future[ModelResponse] =
    connection match
      case Left(reason)  => Future.failed(ModelCallFailed(name, reason))
      case Right(client) =>
        val outgoing    = idsFor(request)
        val traceparent = OpenAiProvider.traceparent
        Future {
          try
            shape match
              case Shape.Responses =>
                ResponsesShape.translate(
                  name,
                  OpenAiProvider.json(
                    client
                      .responses()
                      .create(ResponsesShape.build(name, outgoing, settings, traceparent))
                  )
                )
              case Shape.ChatCompletions =>
                ChatCompletionsShape.translate(
                  OpenAiProvider.json(
                    client
                      .chat()
                      .completions()
                      .create(
                        ChatCompletionsShape.build(outgoing, settings, stream = false, traceparent)
                      )
                  )
                )
          catch case NonFatal(failure) => throw OpenAiProvider.failed(name, failure)
        }

  /**
   * Streams a response as it is generated: text deltas as they arrive, and a terminal `Completed`
   * chunk carrying the same tool calls, stop reason and usage a completion would.
   */
  override def stream(request: ModelRequest): Source[ModelChunk, NotUsed] =
    connection match
      case Left(reason)  => Source.failed(ModelCallFailed(name, reason))
      case Right(client) =>
        val outgoing    = idsFor(request)
        val traceparent = OpenAiProvider.traceparent
        Source
          .unfoldResource[Option[ModelChunk], OpenAiStreaming](
            () =>
              try
                shape match
                  case Shape.Responses =>
                    ResponsesShape.Streaming(
                      name,
                      client
                        .responses()
                        .createStreaming(
                          ResponsesShape.build(name, outgoing, settings, traceparent)
                        )
                    )
                  case Shape.ChatCompletions =>
                    ChatCompletionsShape.Streaming(
                      client
                        .chat()
                        .completions()
                        .createStreaming(
                          ChatCompletionsShape.build(outgoing, settings, stream = true, traceparent)
                        )
                    )
              catch case NonFatal(failure) => throw OpenAiProvider.failed(name, failure),
            state =>
              try state.next()
              catch case NonFatal(failure) => throw OpenAiProvider.failed(name, failure),
            state => state.close()
          )
          .withAttributes(ActorAttributes.dispatcher("pekko.actor.default-blocking-io-dispatcher"))
          .collect { case Some(chunk) => chunk }
          .mapMaterializedValue(_ => NotUsed)

  /** The request as this endpoint takes it: ids rewritten where it constrains them. */
  private def idsFor(request: ModelRequest): ModelRequest =
    quirks.toolCallIds match
      case Some(rule) => request.copy(messages = ToolCallIds.rewrite(request.messages, rule))
      case None       => request

object OpenAiProvider:

  /**
   * The calling thread's trace, as the header that carries it to the endpoint: read before the call
   * moves to the SDK's thread, which cannot see it, so a server that traces (the installation's
   * model server does) records its span in the turn's trace.
   */
  private def traceparent: Option[String] =
    com.thinkmorestupidless.ankka.runtime.Trace.currentContext
      .map(com.thinkmorestupidless.ankka.runtime.Traceparent.render)

  /**
   * The current general-purpose OpenAI model, as of October 2026.
   *
   * Choosing a cheaper model is a decision for whoever runs the service, not a default ankka should
   * make on their behalf.
   */
  val DefaultModel: String = "gpt-6.1-sol"

  /** The name a provider of OpenAI's own API answers under unless given another. */
  val Name: String = "openai"

  /** OpenAI's own address. */
  val Address: String = "https://api.openai.com/v1"

  /**
   * What a request is sent with when it does not say: the most tokens, whether the model may
   * reason, how hard, and a sampling temperature, which a reasoning model refuses and is withheld
   * from it.
   */
  final case class Defaults(
      maxTokens: Long = 16000L,
      thinking: Boolean = true,
      effort: Option[Effort] = None,
      temperature: Option[Double] = None
  )

  private val KeyVariable = "OPENAI_API_KEY"

  private val log = LoggerFactory.getLogger(classOf[OpenAiProvider])

  /**
   * OpenAI's own API, with `OPENAI_API_KEY` (and the SDK's other variables, `OPENAI_BASE_URL` among
   * them) from the environment, in the Responses shape, named `openai`.
   *
   * Without the key the provider still exists and every call fails naming the variable, so a
   * service with one vendor's key and not the other's starts and refuses only the work that needs
   * this one.
   */
  def fromEnv(modelName: String = DefaultModel, defaults: Defaults = Defaults()): OpenAiProvider =
    if sys.env.get(KeyVariable).exists(_.nonEmpty) then
      val client = OpenAIOkHttpClient.builder().fromEnv().maxRetries(0).build()
      new OpenAiProvider(Right(client), Name, modelName, Shape.Responses, defaults, Quirks.openAi)
    else withoutKey(modelName, defaults)

  /**
   * The same, from the variables given rather than the process's: `OPENAI_API_KEY` and
   * `OPENAI_BASE_URL`.
   */
  def fromEnv(env: Map[String, String], modelName: String, defaults: Defaults): OpenAiProvider =
    env.get(KeyVariable).filter(_.nonEmpty) match
      case None      => withoutKey(modelName, defaults)
      case Some(key) =>
        val address = env.get("OPENAI_BASE_URL").filter(_.nonEmpty).getOrElse(Address)
        new OpenAiProvider(
          Right(client(address, Some(key))),
          Name,
          modelName,
          Shape.Responses,
          defaults,
          Quirks.openAi
        )

  private def withoutKey(modelName: String, defaults: Defaults): OpenAiProvider =
    log.warn(
      "model provider '{}' has no key: {} is not set, so every call to it fails until it is",
      Name,
      KeyVariable
    )
    new OpenAiProvider(
      Left(s"$KeyVariable is not set"),
      Name,
      modelName,
      Shape.Responses,
      defaults,
      Quirks.openAi
    )

  def withApiKey(
      apiKey: String,
      modelName: String = DefaultModel,
      defaults: Defaults = Defaults()
  ): OpenAiProvider =
    new OpenAiProvider(
      Right(client(Address, Some(apiKey))),
      Name,
      modelName,
      Shape.Responses,
      defaults,
      Quirks.openAi
    )

  /** For a pre-configured client: a proxy, a custom timeout, Azure with Entra credentials. */
  def apply(
      client: OpenAIClient,
      modelName: String = DefaultModel,
      shape: Shape = Shape.Responses,
      defaults: Defaults = Defaults(),
      name: String = Name
  ): OpenAiProvider =
    new OpenAiProvider(Right(client), name, modelName, shape, defaults, Quirks.openAi)

  /**
   * OpenAI's protocol at any address, under a name the service chose: in Chat Completions unless
   * told otherwise, with a bare endpoint's quirks unless given a preset's. With no key the
   * placeholder `none` is sent, which a local server ignores.
   */
  def at(
      name: String,
      address: String,
      modelName: String,
      key: Option[String] = None,
      shape: Shape = Shape.ChatCompletions,
      defaults: Defaults = Defaults(),
      quirks: Quirks = Quirks.compatibleOpenAi,
      tls: Option[ClientTls] = None
  ): OpenAiProvider =
    new OpenAiProvider(Right(client(address, key, tls)), name, modelName, shape, defaults, quirks)

  /**
   * The certificate a call presents and the servers it accepts, for an address that asks for one.
   */
  final case class ClientTls(socketFactory: SSLSocketFactory, trustManager: X509TrustManager)

  /**
   * Nothing is retried here: a failed call is the provider's failure, as ankka treats every one.
   */
  private def client(
      address: String,
      key: Option[String],
      tls: Option[ClientTls] = None
  ): OpenAIClient =
    val builder = OpenAIOkHttpClient
      .builder()
      .baseUrl(address)
      .apiKey(key.filter(_.nonEmpty).getOrElse(ModelProviders.NoKey))
      .maxRetries(0)
    tls.foreach(t => builder.sslSocketFactory(t.socketFactory).trustManager(t.trustManager))
    builder.build()

  /** An SDK value as ankka's JSON, through the SDK's own mapper. */
  private[openai] def json(value: AnyRef): Json =
    Json
      .parse(ObjectMappers.jsonMapper().writeValueAsString(value))
      .fold(
        problem => throw IllegalStateException(s"the endpoint's answer is not JSON: $problem"),
        identity
      )

  /** ankka's JSON as the SDK's, for the parts of a request ankka builds whole. */
  private[openai] def jsonValue(value: Json): JsonValue = JsonValue.from(toJava(value))

  private def toJava(value: Json): Any = value match
    case Json.Null    => null
    case Json.Bool(v) => java.lang.Boolean.valueOf(v)
    case Json.Str(v)  => v
    case Json.Num(v)  =>
      if v.isWhole && v.abs <= Long.MaxValue.toDouble then java.lang.Long.valueOf(v.toLong)
      else java.lang.Double.valueOf(v)
    case Json.Arr(items)  => items.map(toJava).asJava
    case Json.Obj(fields) => fields.map((key, field) => key -> toJava(field)).asJava

  /**
   * A failure in ankka's words: the provider's name, and the endpoint's status and message or what
   * stopped the connection. Never the SDK's own: a provider the service named `local` must not
   * report itself as a vendor's.
   */
  private[agent] def failed(name: String, failure: Throwable): ModelCallFailed =
    failure match
      case already: ModelCallFailed        => already
      case service: OpenAIServiceException =>
        ModelCallFailed(
          name,
          s"answered ${service.statusCode()}: ${AnthropicProvider.summary(ObjectMappers.jsonMapper().writeValueAsString(service.body()))}"
        )
      case other =>
        ModelCallFailed(
          name,
          s"could not be reached: ${AnthropicProvider.rootMessage(other)}",
          Some(other)
        )

/** One stream's progress, pulled one event at a time. */
private[openai] trait OpenAiStreaming:
  /** `None` ends the stream; `Some(None)` is an event that produces no chunk. */
  def next(): Option[Option[ModelChunk]]
  def close(): Unit

/**
 * The settings a request is sent with, worked out once per provider from its defaults and quirks.
 */
private[openai] final case class OpenAiSettings(defaults: OpenAiProvider.Defaults, quirks: Quirks):

  def maxTokens(request: ModelRequest): Long =
    request.settings.maxTokens.map(_.toLong).getOrElse(defaults.maxTokens)

  /** A temperature, where the endpoint takes one. */
  def temperature(request: ModelRequest): Option[Double] =
    if quirks.acceptsTemperature then request.settings.temperature.orElse(defaults.temperature)
    else None

  /**
   * The reasoning effort to send, if any.
   *
   * Asked for and known: the highest the endpoint offers at or below what was asked. Thinking off:
   * the lowest it offers, or nothing where none is known. Nothing known: what was asked, as asked,
   * and only when it was.
   */
  def reasoningEffort(request: ModelRequest): Option[String] =
    val thinking = request.settings.thinking && defaults.thinking
    val asked    = request.settings.effort.orElse(defaults.effort).map(OpenAiSettings.word)
    quirks.reasoningEfforts match
      case None                            => if thinking then asked else None
      case Some(levels) if levels.nonEmpty =>
        if !thinking then levels.headOption
        else
          asked.map { word =>
            val rank = Quirks.EffortOrder.indexOf(word)
            levels
              .filter(l => Quirks.EffortOrder.indexOf(l) <= rank)
              .lastOption
              .getOrElse(levels.head)
          }
      case Some(_) => None

  /** Whether reasoning items are asked for: only where the endpoint is known to reason. */
  def reasons: Boolean = quirks.reasoningEfforts.exists(_.nonEmpty)

private[openai] object OpenAiSettings:
  def word(effort: Effort): String = effort match
    case Effort.Low    => "low"
    case Effort.Medium => "medium"
    case Effort.High   => "high"
    case Effort.XHigh  => "xhigh"
    case Effort.Max    => "max"

/** What both shapes read from an answer. */
private[openai] object Answers:

  /**
   * A tool call's arguments, which this protocol sends as a JSON string; unreadable ones are empty.
   */
  def arguments(text: Option[String]): Json =
    text
      .flatMap(t => Json.parse(t).toOption)
      .collect { case o: Json.Obj => o }
      .getOrElse(Json.Obj(Map.empty))

  def int(json: Option[Json]): Int = json.flatMap(_.asDouble).map(_.toInt).getOrElse(0)

  /**
   * Usage in ankka's units. The protocol counts cached prompt tokens inside the prompt's; ankka
   * counts them as cache reads beside it, as Anthropic does, so a session's columns mean one thing
   * whichever provider answered. No usage reported is none.
   */
  def usage(prompt: Int, cached: Int, output: Int): TokenUsage =
    TokenUsage(
      inputTokens = math.max(prompt - cached, 0),
      outputTokens = output,
      cacheReadTokens = cached,
      cacheWriteTokens = 0
    )

  /**
   * A tool result as the model is told it: an error is said to be one, since the protocol has no
   * flag.
   */
  def toolOutput(result: ToolResult): String =
    if result.isError then s"Error: ${result.content}" else result.content
