package com.thinkmorestupidless.ankka.agent.judgment

import com.thinkmorestupidless.ankka.agent.{Json, TokenUsage}
import com.thinkmorestupidless.ankka.runtime.AnkkaExecutors

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, HttpTimeoutException}
import java.time.Duration
import scala.collection.immutable.ListMap
import scala.concurrent.Future
import scala.util.Try

/**
 * TypeSafe AI's Jev, the first System One model: one request per judgment to its evaluation
 * endpoint.
 *
 * Straight-line blocking code on a virtual thread, as both agent loops are: send, and on a failure
 * the provider's own clients treat as transient — a request timeout, rate limiting, a server error,
 * a connection that could not be made — wait and send again, until the judgment's timeout. A
 * refused key and an invalid request are never retried.
 *
 * The key is sent in one header and nowhere else. No error is built from a request's headers and
 * the state is never put in one; an error carries the response's status and body.
 */
final class JevProvider private (
    apiKey: String,
    val modelName: String,
    baseUrl: String
) extends JudgmentProvider:

  def name: String = "jev"

  private val endpoint = URI.create(s"${baseUrl.stripSuffix("/")}/v1/systemone")
  private val client   = HttpClient.newHttpClient()

  def judge(request: JudgmentRequest): Future[Judgment] =
    Future(run(request))(using AnkkaExecutors.virtual)

  private def run(request: JudgmentRequest): Judgment =
    val deadline        = System.nanoTime() + request.timeout.toNanos
    def remaining: Long = deadline - System.nanoTime()
    def timedOut =
      JudgmentFailed(name, s"did not answer within ${request.timeout}", timedOut = true)
    val body             = JevProvider.encode(modelName, request).render
    var attempt          = 1
    var result: Judgment = null

    while result == null do
      if remaining <= 0 then throw timedOut
      val http = HttpRequest
        .newBuilder(endpoint)
        .timeout(Duration.ofNanos(remaining))
        .header("Authorization", s"Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()

      // Right: answered. Left: a transient failure, with the wait the provider asked for, if any.
      val outcome: Either[(String, Option[Long]), Judgment] =
        try
          val response = client.send(http, HttpResponse.BodyHandlers.ofString())
          val status   = response.statusCode
          if status == 200 then Right(JevProvider.decode(name, response.body))
          else if JevProvider.transient(status) then
            Left(s"$status: ${JevProvider.clip(response.body)}" -> JevProvider.waitHint(response))
          else throw JudgmentFailed(name, s"$status: ${JevProvider.clip(response.body)}")
        catch
          case _: HttpTimeoutException => throw timedOut
          case failure: java.io.IOException =>
            Left(s"could not reach $endpoint: ${failure.getClass.getSimpleName}" -> None)

      outcome match
        case Right(judgment) => result = judgment
        case Left((message, hint)) =>
          val waitMillis = hint.getOrElse(JevProvider.backoff(attempt))
          if waitMillis * 1_000_000L >= remaining then throw JudgmentFailed(name, message)
          Thread.sleep(waitMillis)
          attempt += 1

    result

  override def toString: String = s"JevProvider($modelName, $baseUrl)"

object JevProvider:

  /**
   * A version, not the `jev-latest` alias: answers can change between versions, and thresholds are
   * tuned against one.
   */
  val DefaultModel: String = "jev-1.13.0"

  val DefaultBaseUrl: String = "https://api.typesafe.ai"

  /** The variables the provider's own clients read. */
  val KeyVariable: String     = "TYPESAFE_API_KEY"
  val BaseUrlVariable: String = "TYPESAFE_BASE_URL"

  /**
   * Reads the key from `TYPESAFE_API_KEY`, and the address from `TYPESAFE_BASE_URL` when it is set.
   * A missing key fails here, at startup, rather than at the first judgment.
   */
  def fromEnv(model: String = DefaultModel, env: Map[String, String] = sys.env): JevProvider =
    val key = env.get(KeyVariable).filter(_.trim.nonEmpty).getOrElse {
      throw IllegalArgumentException(s"$KeyVariable is not set")
    }
    new JevProvider(
      key,
      model,
      env.get(BaseUrlVariable).filter(_.trim.nonEmpty).getOrElse(DefaultBaseUrl)
    )

  def withApiKey(
      apiKey: String,
      model: String = DefaultModel,
      baseUrl: String = DefaultBaseUrl
  ): JevProvider =
    if apiKey.trim.isEmpty then throw IllegalArgumentException("an API key is required")
    new JevProvider(apiKey, model, baseUrl)

  private val InitialBackoffMillis = 250L
  private val MaxBackoffMillis     = 5_000L

  private def transient(status: Int): Boolean = status == 408 || status == 429 || status >= 500

  private def backoff(attempt: Int): Long =
    math.min(MaxBackoffMillis, InitialBackoffMillis << math.min(attempt - 1, 10))

  private def waitHint(response: HttpResponse[?]): Option[Long] =
    def header(name: String) = Option(response.headers.firstValue(name).orElse(null))
    header("retry-after-ms")
      .flatMap(v => Try(v.trim.toDouble.toLong).toOption)
      .orElse(header("retry-after").flatMap(v => Try(v.trim.toLong * 1000L).toOption))
      .map(math.max(0L, _))

  /** A body long enough to be useful in an error, and no longer. */
  private def clip(body: String): String =
    if body.length <= 1000 then body else body.take(1000) + "…"

  // ── The wire ──────────────────────────────────────────────────────────────

  private[judgment] def encode(model: String, request: JudgmentRequest): Json =
    val state = request.state match
      case JudgmentState.Text(text)        => Json.str(text)
      case JudgmentState.Structured(value) => value
    Json.Obj(
      ListMap(
        "model"     -> Json.str(model),
        "state"     -> state,
        "questions" -> Json.Obj(ListMap.from(request.questions.map(q => q.id -> question(q))))
      )
    )

  private def question(q: Question[?]): Json = q match
    case c: ChoiceQuestion[?] =>
      Json.Obj(
        ListMap(
          "type"         -> Json.str("choice"),
          "instructions" -> Json.str(c.instructions),
          "criteria" -> Json.Obj(ListMap.from(c.options.map(o => o.key -> Json.str(o.description))))
        )
      )
    case s: ScoreQuestion =>
      Json.Obj(
        ListMap(
          "type"         -> Json.str("score"),
          "instructions" -> Json.str(s.instructions),
          "criteria"     -> Json.Arr(s.levels.map(Json.str))
        )
      )
    case y: YesNoQuestion =>
      val described = y.described.map { (yes, no) =>
        "criteria" -> Json.Obj(ListMap("true" -> Json.str(yes), "false" -> Json.str(no)))
      }
      Json.Obj(
        ListMap.from(
          Seq("type" -> Json.str("noul"), "instructions" -> Json.str(y.instructions)) ++ described
        )
      )

  private[judgment] def decode(provider: String, body: String): Judgment =
    def fail(message: String): Nothing = throw JudgmentFailed(provider, message)
    val root  = Json.parse(body).fold(_ => fail("the response is not JSON"), identity)
    val model = root("model").flatMap(_.asString).getOrElse(fail("the response has no 'model'"))
    val answers = root("answers") match
      case Some(Json.Obj(fields)) => fields
      case _                      => fail("the response has no 'answers'")
    val usage = root("usage") match
      case Some(u @ Json.Obj(_)) =>
        def tokens(field: String) = u(field).flatMap(_.asDouble).map(_.toInt).getOrElse(0)
        TokenUsage(inputTokens = tokens("input_tokens"), outputTokens = tokens("output_tokens"))
      case _ => fail("the response has no 'usage'")

    Judgment(model, answers.map((id, answer) => id -> stored(provider, id, answer)), usage)

  private def stored(provider: String, id: String, answer: Json): Judgment.Stored =
    def fail(message: String): Nothing = throw JudgmentFailed(provider, s"question '$id': $message")
    def number(field: String): Double =
      answer(field).flatMap(_.asDouble).getOrElse(fail(s"the answer has no '$field'"))
    def probabilities: Map[String, Double] = answer("probabilities") match
      case Some(Json.Obj(fields)) =>
        fields.map((k, v) =>
          k -> v.asDouble.getOrElse(fail(s"the probability of '$k' is not a number"))
        )
      case _ => fail("the answer has no 'probabilities'")

    answer("type").flatMap(_.asString) match
      case Some("choice") =>
        val key = answer("choice").flatMap(_.asString).getOrElse(fail("the answer has no 'choice'"))
        Judgment.Stored.Choice(key, probabilities, number("confidence"))
      case Some("score") =>
        val byLevel = probabilities
        val levels  = byLevel.keys.flatMap(_.toIntOption).toVector.sorted
        if levels.sizeIs != byLevel.size || levels != levels.indices.toVector then
          fail("the answer's probabilities are not keyed by level number")
        Judgment.Stored.Score(
          number("score"),
          levels.map(l => byLevel(l.toString)),
          number("confidence")
        )
      case Some("noul") => Judgment.Stored.YesNo(number("noul"))
      case Some(other)  => fail(s"the answer is of an unknown type '$other'")
      case None         => fail("the answer has no 'type'")
