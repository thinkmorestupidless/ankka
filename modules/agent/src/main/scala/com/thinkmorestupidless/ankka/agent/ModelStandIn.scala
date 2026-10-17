package com.thinkmorestupidless.ankka.agent

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors}
import scala.jdk.CollectionConverters.*

/**
 * A model server on loopback that speaks Anthropic's Messages and OpenAI's Responses and Chat
 * Completions, from one script.
 *
 * What a model says is scripted once, as ankka's own `ModelResponse`, and rendered in whichever
 * shape a request arrives in, streamed when the request asks. Every request is recorded as JSON, so
 * a test asserts on what a provider sent. It interprets no prompt: the script decides, and a
 * request with nothing scripted for it is answered 418, so a test that forgot a reply fails loudly.
 *
 * {{{
 * val standIn = ModelStandIn.start()
 * standIn.answer(ModelResponse("Paris"))
 * val local = OpenAiProvider.at("local", standIn.address, "qwen3:8b")
 * }}}
 */
final class ModelStandIn private (server: HttpServer):

  private val script    = ConcurrentLinkedQueue[ModelResponse]()
  private val received  = ConcurrentLinkedQueue[ModelStandIn.Received]()
  private val failures  = ConcurrentLinkedQueue[(Int, String)]()
  private val noUsage   = AtomicBoolean(false)
  private val reasoning = AtomicBoolean(false)
  private val replay    = AtomicBoolean(false)
  private val rejected  = AtomicReference(Set.empty[String])
  private val held      = AtomicReference(Option.empty[CountDownLatch])
  private val counter   = AtomicInteger(0)
  // Each call id this server issued with the reasoning item that led to it.
  private val issued = java.util.concurrent.ConcurrentHashMap[String, String]()

  /** Where the server listens: `http://127.0.0.1:<port>`, with no path. */
  def address: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** Queues what the model answers next. Answers are consumed in order. */
  def answer(response: ModelResponse): ModelStandIn =
    script.add(response): Unit
    this

  /** The answers from now on report no usage. */
  def reportNoUsage(): ModelStandIn =
    noUsage.set(true)
    this

  /** A request carrying this top-level field is refused with a 400 naming it. */
  def rejectSetting(field: String): ModelStandIn =
    rejected.updateAndGet(_ + field): Unit
    this

  /** The next request is answered with this status and body, and consumes nothing scripted. */
  def failNext(
      status: Int,
      body: String = """{"error":{"message":"scripted failure"}}"""
  ): ModelStandIn =
    failures.add(status -> body): Unit
    this

  /** Requests from now on are not answered until `release`. */
  def hang(): ModelStandIn =
    held.set(Some(CountDownLatch(1)))
    this

  def release(): Unit = held.getAndSet(None).foreach(_.countDown())

  /**
   * On the Responses shape, a tool call is preceded by a reasoning item, as a reasoning model's is.
   */
  def reasons(): ModelStandIn =
    reasoning.set(true)
    this

  /**
   * On the Responses shape, a tool's result that does not bring back the reasoning item issued with
   * its call is refused with a 400, as OpenAI's reasoning models refuse it when nothing is stored.
   * Implies `reasons()`.
   */
  def requireReasoningReplay(): ModelStandIn =
    reasoning.set(true)
    replay.set(true)
    this

  /** Every request, in order. */
  def requests: Vector[ModelStandIn.Received] = received.asScala.toVector

  def lastRequest: ModelStandIn.Received =
    requests.lastOption.getOrElse(throw AssertionError("the stand-in was never called"))

  def close(): Unit =
    release()
    server.stop(0)

  // ── Serving ───────────────────────────────────────────────────────────────

  private[agent] def handle(exchange: HttpExchange): Unit =
    try
      val path = exchange.getRequestURI.getPath
      val text = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val body = Json.parse(text).getOrElse(Json.Null)
      val headers = exchange.getRequestHeaders.asScala
        .map((k, v) => k.toLowerCase -> v.asScala.mkString(","))
        .toMap
      received.add(ModelStandIn.Received(path, headers, body)): Unit
      held.get.foreach(_.await())
      val shape = path match
        case p if p.endsWith("/messages")         => Some(ModelStandIn.Messages)
        case p if p.endsWith("/responses")        => Some(ModelStandIn.Responses)
        case p if p.endsWith("/chat/completions") => Some(ModelStandIn.ChatCompletions)
        case _                                    => None
      val refusedField = body match
        case Json.Obj(fields) => rejected.get.find(fields.contains)
        case _                => None
      shape match
        case None => send(exchange, 404, s"""{"error":{"message":"no such path: $path"}}""")
        case Some(_) if !failures.isEmpty =>
          val (status, failure) = failures.poll()
          send(exchange, status, failure)
        case Some(_) if refusedField.isDefined =>
          send(
            exchange,
            400,
            s"""{"error":{"message":"Unsupported parameter: '${refusedField.get}'"}}"""
          )
        case Some(ModelStandIn.Responses) if replay.get && missingReasoning(body).isDefined =>
          send(
            exchange,
            400,
            s"""{"error":{"message":"Item '${missingReasoning(
                body
              ).get}' of type 'reasoning' was not provided with its function call"}}"""
          )
        case Some(s) =>
          Option(script.poll()) match
            case None =>
              send(
                exchange,
                418,
                """{"error":{"message":"nothing is scripted for this request"}}"""
              )
            case Some(response) =>
              val model     = body("model").flatMap(_.asString).getOrElse("unknown")
              val streaming = body("stream").flatMap(_.asBoolean).contains(true)
              if streaming then stream(exchange, s, model, response, body)
              else send(exchange, 200, render(s, model, response).render)
    catch
      case failure: Throwable =>
        try
          send(
            exchange,
            500,
            s"""{"error":{"message":"the stand-in failed: ${failure.getMessage}"}}"""
          )
        catch case _: Throwable => ()

  /**
   * The reasoning item a tool's result came back without, if any. Only the current turn's calls are
   * held to it — those since the last user message — as OpenAI holds them: an earlier turn's call
   * is sent without its reasoning, which was never kept.
   */
  private def missingReasoning(body: Json): Option[String] =
    val items                        = body("input").flatMap(_.asArray).getOrElse(Vector.empty)
    def typed(item: Json, t: String) = item("type").flatMap(_.asString).contains(t)
    val turnBegan = items.lastIndexWhere(i => i("role").flatMap(_.asString).contains("user"))
    items.zipWithIndex.collectFirst(Function.unlift { (item, index) =>
      if index < turnBegan || !typed(item, "function_call_output") then None
      else
        val callId = item("call_id").flatMap(_.asString).getOrElse("")
        Option(issued.get(callId)).flatMap { reasoningId =>
          val callAt = items.indexWhere(i =>
            typed(i, "function_call") && i("call_id").flatMap(_.asString).contains(callId)
          )
          val reasoningAt =
            items.indexWhere(i =>
              typed(i, "reasoning") && i("id").flatMap(_.asString).contains(reasoningId)
            )
          if callAt >= 0 && reasoningAt >= 0 && reasoningAt < callAt && callAt < index then None
          else Some(reasoningId)
        }
    })

  private def next(prefix: String): String = s"$prefix${counter.incrementAndGet()}"

  // ── Rendering ─────────────────────────────────────────────────────────────

  private def render(shape: ModelStandIn.Shape, model: String, response: ModelResponse): Json =
    shape match
      case ModelStandIn.Messages        => messages(model, response)
      case ModelStandIn.Responses       => responses(model, response)
      case ModelStandIn.ChatCompletions => chat(model, response)

  private def usageOr(fields: => Vector[(String, Json)]): Vector[(String, Json)] =
    if noUsage.get then Vector.empty else fields

  private def num(i: Int): Json = Json.num(i.toDouble)

  private def messages(model: String, response: ModelResponse): Json =
    val text =
      if response.text.isEmpty then Vector.empty
      else Vector(Json.obj("type" -> Json.str("text"), "text" -> Json.str(response.text)))
    val calls = response.toolCalls.map { call =>
      Json.obj(
        "type"  -> Json.str("tool_use"),
        "id"    -> Json.str(call.id),
        "name"  -> Json.str(call.name),
        "input" -> call.arguments
      )
    }
    val u = response.usage
    Json.Obj(
      (Vector(
        "id"            -> Json.str(next("msg_")),
        "type"          -> Json.str("message"),
        "role"          -> Json.str("assistant"),
        "model"         -> Json.str(model),
        "content"       -> Json.Arr(text ++ calls),
        "stop_reason"   -> Json.str(ModelStandIn.anthropicStop(response.stopReason)),
        "stop_sequence" -> Json.Null
      ) ++ response.refusalReason.map(r =>
        "stop_details" -> Json.obj("type" -> Json.str("refusal"), "explanation" -> Json.str(r))
      ) ++ Vector(
        "usage" -> Json.obj(
          "input_tokens"                -> num(if noUsage.get then 0 else u.inputTokens),
          "output_tokens"               -> num(if noUsage.get then 0 else u.outputTokens),
          "cache_read_input_tokens"     -> num(if noUsage.get then 0 else u.cacheReadTokens),
          "cache_creation_input_tokens" -> num(if noUsage.get then 0 else u.cacheWriteTokens)
        )
      )).toMap
    )

  private def responsesOutput(response: ModelResponse): Vector[Json] =
    val said =
      if response.isRefusal then
        Vector(
          Json.obj(
            "type"   -> Json.str("message"),
            "id"     -> Json.str(next("msg_")),
            "role"   -> Json.str("assistant"),
            "status" -> Json.str("completed"),
            "content" -> Json.arr(
              Json.obj(
                "type"    -> Json.str("refusal"),
                "refusal" -> Json.str(response.refusalReason.getOrElse("declined"))
              )
            )
          )
        )
      else if response.text.isEmpty then Vector.empty
      else
        Vector(
          Json.obj(
            "type"   -> Json.str("message"),
            "id"     -> Json.str(next("msg_")),
            "role"   -> Json.str("assistant"),
            "status" -> Json.str("completed"),
            "content" -> Json.arr(
              Json.obj(
                "type"        -> Json.str("output_text"),
                "text"        -> Json.str(response.text),
                "annotations" -> Json.arr()
              )
            )
          )
        )
    val calls = response.toolCalls.flatMap { call =>
      val reasoned =
        if !reasoning.get then Vector.empty
        else
          val id = next("rs_")
          issued.put(call.id, id): Unit
          Vector(
            Json.obj(
              "type"              -> Json.str("reasoning"),
              "id"                -> Json.str(id),
              "summary"           -> Json.arr(),
              "encrypted_content" -> Json.str(s"enc-$id")
            )
          )
      reasoned :+ Json.obj(
        "type"      -> Json.str("function_call"),
        "id"        -> Json.str(next("fc_")),
        "call_id"   -> Json.str(call.id),
        "name"      -> Json.str(call.name),
        "arguments" -> Json.str(call.arguments.render),
        "status"    -> Json.str("completed")
      )
    }
    said ++ calls

  private def responses(
      model: String,
      response: ModelResponse,
      output: Option[Vector[Json]] = None
  ): Json =
    val u      = response.usage
    val cutOff = response.stopReason == StopReason.MaxTokens
    Json.Obj(
      (Vector(
        "id"                  -> Json.str(next("resp_")),
        "object"              -> Json.str("response"),
        "created_at"          -> num(0),
        "status"              -> Json.str(if cutOff then "incomplete" else "completed"),
        "model"               -> Json.str(model),
        "output"              -> Json.Arr(output.getOrElse(responsesOutput(response))),
        "parallel_tool_calls" -> Json.bool(true),
        "tool_choice"         -> Json.str("auto"),
        "tools"               -> Json.arr(),
        "error"               -> Json.Null,
        "incomplete_details" ->
          (if cutOff then Json.obj("reason" -> Json.str("max_output_tokens")) else Json.Null)
      ) ++ usageOr(
        Vector(
          "usage" -> Json.obj(
            "input_tokens"          -> num(u.inputTokens + u.cacheReadTokens),
            "input_tokens_details"  -> Json.obj("cached_tokens" -> num(u.cacheReadTokens)),
            "output_tokens"         -> num(u.outputTokens),
            "output_tokens_details" -> Json.obj("reasoning_tokens" -> num(0)),
            "total_tokens"          -> num(u.inputTokens + u.cacheReadTokens + u.outputTokens)
          )
        )
      )).toMap
    )

  private def chatUsage(u: TokenUsage): Json =
    Json.obj(
      "prompt_tokens"         -> num(u.inputTokens + u.cacheReadTokens),
      "completion_tokens"     -> num(u.outputTokens),
      "total_tokens"          -> num(u.inputTokens + u.cacheReadTokens + u.outputTokens),
      "prompt_tokens_details" -> Json.obj("cached_tokens" -> num(u.cacheReadTokens))
    )

  private def chat(model: String, response: ModelResponse): Json =
    val message = Json.Obj(
      (Vector(
        "role"    -> Json.str("assistant"),
        "content" -> (if response.text.isEmpty then Json.Null else Json.str(response.text)),
        "refusal" -> response.refusalReason
          .filter(_ => response.isRefusal)
          .map(Json.str)
          .getOrElse(Json.Null)
      ) ++ (if response.toolCalls.isEmpty then Vector.empty
            else
              Vector("tool_calls" -> Json.Arr(response.toolCalls.map { call =>
                Json.obj(
                  "id"   -> Json.str(call.id),
                  "type" -> Json.str("function"),
                  "function" -> Json.obj(
                    "name"      -> Json.str(call.name),
                    "arguments" -> Json.str(call.arguments.render)
                  )
                )
              })))).toMap
    )
    Json.Obj(
      (Vector(
        "id"      -> Json.str(next("chatcmpl-")),
        "object"  -> Json.str("chat.completion"),
        "created" -> num(0),
        "model"   -> Json.str(model),
        "choices" -> Json.arr(
          Json.obj(
            "index"         -> num(0),
            "message"       -> message,
            "finish_reason" -> Json.str(ModelStandIn.chatFinish(response)),
            "logprobs"      -> Json.Null
          )
        )
      ) ++ usageOr(Vector("usage" -> chatUsage(response.usage)))).toMap
    )

  // ── Streaming ─────────────────────────────────────────────────────────────

  private def stream(
      exchange: HttpExchange,
      shape: ModelStandIn.Shape,
      model: String,
      response: ModelResponse,
      body: Json
  ): Unit =
    exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, 0)
    val out = exchange.getResponseBody
    def event(name: Option[String], data: String): Unit =
      name.foreach(n => out.write(s"event: $n\n".getBytes(StandardCharsets.UTF_8)))
      out.write(s"data: $data\n\n".getBytes(StandardCharsets.UTF_8))
      out.flush()
    val words =
      if response.text.isEmpty then Vector.empty
      else
        response.text.split(" ").toVector.zipWithIndex.map((w, i) => if i == 0 then w else s" $w")
    shape match
      case ModelStandIn.Messages        => streamMessages(event, model, response, words)
      case ModelStandIn.Responses       => streamResponses(event, model, response, words)
      case ModelStandIn.ChatCompletions => streamChat(event, model, response, words, body)
    out.close()

  private def streamMessages(
      event: (Option[String], String) => Unit,
      model: String,
      response: ModelResponse,
      words: Vector[String]
  ): Unit =
    val whole = messages(model, response)
    def send(t: String, fields: (String, Json)*): Unit =
      event(Some(t), Json.Obj((("type" -> Json.str(t)) +: fields).toMap).render)
    val start = whole match
      case Json.Obj(fields) =>
        Json.Obj(
          fields ++ Map("content" -> Json.arr(), "stop_reason" -> Json.Null) - "stop_details"
        )
      case other => other
    send("message_start", "message" -> start)
    var index = 0
    if words.nonEmpty then
      send(
        "content_block_start",
        "index"         -> num(index),
        "content_block" -> Json.obj("type" -> Json.str("text"), "text" -> Json.str(""))
      )
      words.foreach { w =>
        send(
          "content_block_delta",
          "index" -> num(index),
          "delta" -> Json.obj("type" -> Json.str("text_delta"), "text" -> Json.str(w))
        )
      }
      send("content_block_stop", "index" -> num(index))
      index += 1
    response.toolCalls.foreach { call =>
      send(
        "content_block_start",
        "index" -> num(index),
        "content_block" -> Json.obj(
          "type"  -> Json.str("tool_use"),
          "id"    -> Json.str(call.id),
          "name"  -> Json.str(call.name),
          "input" -> Json.obj()
        )
      )
      val args = call.arguments.render
      args.grouped(math.max(1, args.length / 2 + 1)).foreach { part =>
        send(
          "content_block_delta",
          "index" -> num(index),
          "delta" -> Json
            .obj("type" -> Json.str("input_json_delta"), "partial_json" -> Json.str(part))
        )
      }
      send("content_block_stop", "index" -> num(index))
      index += 1
    }
    send(
      "message_delta",
      "delta" -> Json.Obj(
        (Vector(
          "stop_reason"   -> Json.str(ModelStandIn.anthropicStop(response.stopReason)),
          "stop_sequence" -> Json.Null
        ) ++ response.refusalReason.map(r =>
          "stop_details" -> Json.obj("type" -> Json.str("refusal"), "explanation" -> Json.str(r))
        )).toMap
      ),
      "usage" -> whole("usage").getOrElse(Json.obj())
    )
    send("message_stop")

  private def streamResponses(
      event: (Option[String], String) => Unit,
      model: String,
      response: ModelResponse,
      words: Vector[String]
  ): Unit =
    val output   = responsesOutput(response)
    val whole    = responses(model, response, Some(output))
    var sequence = 0
    def send(t: String, fields: (String, Json)*): Unit =
      sequence += 1
      event(
        Some(t),
        Json
          .Obj((Vector("type" -> Json.str(t), "sequence_number" -> num(sequence)) ++ fields).toMap)
          .render
      )
    val started = whole match
      case Json.Obj(fields) =>
        Json.Obj(fields ++ Map("status" -> Json.str("in_progress"), "output" -> Json.arr()))
      case other => other
    send("response.created", "response" -> started)
    output.zipWithIndex.foreach { (item, index) =>
      send("response.output_item.added", "output_index" -> num(index), "item" -> item)
      if item("type").flatMap(_.asString).contains("message") && words.nonEmpty then
        words.foreach { w =>
          send(
            "response.output_text.delta",
            "item_id"       -> item("id").getOrElse(Json.Null),
            "output_index"  -> num(index),
            "content_index" -> num(0),
            "delta"         -> Json.str(w),
            "logprobs"      -> Json.arr()
          )
        }
      send("response.output_item.done", "output_index" -> num(index), "item" -> item)
    }
    val terminal =
      if response.stopReason == StopReason.MaxTokens then "response.incomplete"
      else "response.completed"
    send(terminal, "response" -> whole)

  private def streamChat(
      event: (Option[String], String) => Unit,
      model: String,
      response: ModelResponse,
      words: Vector[String],
      body: Json
  ): Unit =
    val id = next("chatcmpl-")
    def chunk(delta: Json, finish: Option[String]): Unit =
      event(
        None,
        Json
          .obj(
            "id"      -> Json.str(id),
            "object"  -> Json.str("chat.completion.chunk"),
            "created" -> num(0),
            "model"   -> Json.str(model),
            "choices" -> Json.arr(
              Json.obj(
                "index"         -> num(0),
                "delta"         -> delta,
                "finish_reason" -> finish.map(Json.str).getOrElse(Json.Null),
                "logprobs"      -> Json.Null
              )
            )
          )
          .render
      )
    chunk(Json.obj("role" -> Json.str("assistant"), "content" -> Json.str("")), None)
    words.foreach(w => chunk(Json.obj("content" -> Json.str(w)), None))
    if response.isRefusal then
      chunk(Json.obj("refusal" -> Json.str(response.refusalReason.getOrElse("declined"))), None)
    response.toolCalls.zipWithIndex.foreach { (call, index) =>
      chunk(
        Json.obj(
          "tool_calls" -> Json.arr(
            Json.obj(
              "index"    -> num(index),
              "id"       -> Json.str(call.id),
              "type"     -> Json.str("function"),
              "function" -> Json.obj("name" -> Json.str(call.name), "arguments" -> Json.str(""))
            )
          )
        ),
        None
      )
      // The arguments in fragments, as a real server sends them: complete only at the end.
      val args = call.arguments.render
      args.grouped(math.max(1, args.length / 3 + 1)).foreach { part =>
        chunk(
          Json.obj(
            "tool_calls" -> Json.arr(
              Json.obj("index" -> num(index), "function" -> Json.obj("arguments" -> Json.str(part)))
            )
          ),
          None
        )
      }
    }
    chunk(Json.obj(), Some(ModelStandIn.chatFinish(response)))
    val askedUsage =
      body("stream_options").flatMap(_("include_usage")).flatMap(_.asBoolean).contains(true)
    if askedUsage && !noUsage.get then
      event(
        None,
        Json
          .obj(
            "id"      -> Json.str(id),
            "object"  -> Json.str("chat.completion.chunk"),
            "created" -> num(0),
            "model"   -> Json.str(model),
            "choices" -> Json.arr(),
            "usage"   -> chatUsage(response.usage)
          )
          .render
      )
    event(None, "[DONE]")

  private def send(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, bytes.length.toLong)
    val out: OutputStream = exchange.getResponseBody
    out.write(bytes)
    out.close()

object ModelStandIn:

  /** One request as it arrived: its path, its headers lower-cased, and its body. */
  final case class Received(path: String, headers: Map[String, String], body: Json):
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

  private[agent] sealed trait Shape
  private[agent] case object Messages        extends Shape
  private[agent] case object Responses       extends Shape
  private[agent] case object ChatCompletions extends Shape

  /** A stand-in on an ephemeral loopback port, answering at once. */
  def start(): ModelStandIn =
    val server  = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val standIn = new ModelStandIn(server)
    server.createContext("/", exchange => standIn.handle(exchange))
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor())
    server.start()
    standIn

  private def anthropicStop(reason: StopReason): String = reason match
    case StopReason.EndTurn   => "end_turn"
    case StopReason.ToolUse   => "tool_use"
    case StopReason.MaxTokens => "max_tokens"
    case StopReason.Refusal   => "refusal"
    case StopReason.Other     => "pause_turn"

  private def chatFinish(response: ModelResponse): String = response.stopReason match
    case StopReason.ToolUse               => "tool_calls"
    case StopReason.MaxTokens             => "length"
    case _ if response.toolCalls.nonEmpty => "tool_calls"
    case _                                => "stop"
