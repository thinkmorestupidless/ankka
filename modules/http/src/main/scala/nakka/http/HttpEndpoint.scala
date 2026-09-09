package nakka.http

import nakka.core.{CommandError, ErrorCode}

import scala.collection.mutable

/** A problem to report back to the caller. */
final case class HttpProblem(status: Int, message: String) extends RuntimeException(message):
  override def fillInStackTrace(): Throwable = this

object HttpProblem:
  def badRequest(message: String): HttpProblem   = HttpProblem(400, message)
  def unauthorized(message: String): HttpProblem = HttpProblem(401, message)
  def forbidden(message: String): HttpProblem    = HttpProblem(403, message)
  def notFound(message: String): HttpProblem     = HttpProblem(404, message)
  def conflict(message: String): HttpProblem     = HttpProblem(409, message)

  /**
   * Maps a component's modelled rejection onto a status code.
   *
   * This is the reason `effects.error` carries an `ErrorCode` rather than just a string: a domain
   * rule violation deep inside an entity surfaces as a 409 at the edge without the endpoint having
   * to know anything about that rule.
   */
  def from(error: CommandError): HttpProblem =
    val status = error.code match
      case ErrorCode.BadRequest   => 400
      case ErrorCode.Unauthorized => 401
      case ErrorCode.Forbidden    => 403
      case ErrorCode.NotFound     => 404
      case ErrorCode.Conflict     => 409
      case ErrorCode.Timeout      => 504
      case ErrorCode.Unavailable  => 503
      case ErrorCode.Internal     => 500
    HttpProblem(status, error.message)

/** What the caller of an endpoint is allowed to do. */
enum Acl:
  /** Nothing gets through. The default posture for anything not explicitly opened up. */
  case DenyAll

  /** Any caller. Correct for a public API; state it deliberately. */
  case AllowAll

  /**
   * A caller-supplied predicate.
   *
   * nakka does not ship a "same service" or "named service" principal, because establishing who the
   * caller actually is needs mTLS or a verified token, and a check against a client-settable header
   * would be security theatre. Plug in a real check here.
   */
  case AllowIf(predicate: RequestContext => Boolean)

private[nakka] final case class EncodedResponse(
    status: Int,
    contentType: String,
    body: Array[Byte]
)

private[nakka] final case class Route(
    method: String,
    template: PathTemplate,
    needsBody: Boolean,
    run: (Vector[String], Array[Byte]) => EncodedResponse
):
  def describe: String = s"$method ${template.render}"

/**
 * A route that answers with a stream of text events rather than one response.
 *
 * Kept as a separate route kind rather than a `ToResponse` instance because the server has to treat
 * it differently all the way down: no content length, no buffering, and the connection stays open.
 */
private[nakka] final case class StreamRoute(
    method: String,
    template: PathTemplate,
    needsBody: Boolean,
    run: (Vector[String], Array[Byte]) => org.apache.pekko.stream.scaladsl.Source[String, ?]
):
  def describe: String = s"$method ${template.render} (SSE)"

/**
 * An HTTP endpoint: the outermost layer, translating requests into component calls.
 *
 * Routes are declared in the constructor body and collected as they are declared, so the endpoint's
 * shape is a value the server can inspect and log at startup.
 *
 * Handler parameters must be annotated with their type — `{ (cartId: String) => ... }` rather than
 * `{ cartId => ... }` — because that annotation is what selects the right arity overload and the
 * right `FromPath` instance.
 */
abstract class HttpEndpoint(val prefix: String):

  /**
   * Who may call this endpoint.
   *
   * Abstract on purpose. Akka denies by default via a missing annotation, which is safe but silent;
   * requiring the decision means nobody ships an endpoint without having thought about who can
   * reach it.
   */
  def acl: Acl

  /**
   * The request currently being handled.
   *
   * Available only on the handler's own thread — see `RequestScope`. Read what you need before
   * handing work to another thread.
   */
  protected def request: RequestContext =
    RequestScope.currentContext.getOrElse(
      throw IllegalStateException(
        "request is only available inside a route handler, on the handler's own thread"
      )
    )

  /** Shorthand for `request.query`. */
  protected def query: QueryParams = request.query

  private val collected        = mutable.ListBuffer.empty[Route]
  private val collectedStreams = mutable.ListBuffer.empty[StreamRoute]

  private[nakka] def routes: Vector[Route]             = collected.toVector
  private[nakka] def streamRoutes: Vector[StreamRoute] = collectedStreams.toVector

  private val prefixSegments: Vector[String] =
    prefix.split('/').iterator.filter(_.nonEmpty).toVector

  private[nakka] def prefixPath: Vector[String] = prefixSegments

  /**
   * Records a route, checking the handler's arity against the template's placeholders.
   *
   * A mismatch throws here — during construction, therefore during startup — so it lands next to
   * the other service-definition failures rather than as a surprise on the first request that
   * happens to match.
   */
  private def add[R](method: String, rawTemplate: String, arity: Int, needsBody: Boolean)(
      run: (Vector[String], Array[Byte]) => R
  )(using response: ToResponse[R]): Unit =
    val template = PathTemplate.parse(rawTemplate)
    if template.arity != arity then
      throw IllegalArgumentException(
        s"$method $prefix$rawTemplate declares ${template.arity} path parameter(s) " +
          s"(${template.parameterNames.mkString(", ")}) but its handler takes $arity"
      )
    collected += Route(
      method,
      template,
      needsBody,
      (args, body) =>
        val value = run(args, body)
        EncodedResponse(response.status(value), response.contentType, response.write(value))
    )

  /**
   * Records a server-sent-events route.
   *
   * Declared with `sse` rather than `get` because the response is open-ended: the handler returns a
   * `Source` and the server holds the connection until it completes.
   */
  private def addStream(method: String, rawTemplate: String, arity: Int, needsBody: Boolean)(
      run: (Vector[String], Array[Byte]) => org.apache.pekko.stream.scaladsl.Source[String, ?]
  ): Unit =
    val template = PathTemplate.parse(rawTemplate)
    if template.arity != arity then
      throw IllegalArgumentException(
        s"$method $prefix$rawTemplate declares ${template.arity} path parameter(s) " +
          s"(${template.parameterNames.mkString(", ")}) but its handler takes $arity"
      )
    collectedStreams += StreamRoute(method, template, needsBody, run)

  /** `GET $prefix$template`, answered as server-sent events. */
  protected def sse[A: FromPath](template: String)(
      handler: A => org.apache.pekko.stream.scaladsl.Source[String, ?]
  ): Unit =
    addStream("GET", template, 1, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template))
    )

  /** `GET $prefix$template` with no path parameters, answered as server-sent events. */
  protected def sse(template: String)(
      handler: () => org.apache.pekko.stream.scaladsl.Source[String, ?]
  ): Unit =
    addStream("GET", template, 0, needsBody = false)((_, _) => handler())

  /** `POST $prefix$template` with a decoded body, answered as server-sent events. */
  protected def sseBody[A: FromPath, Body: FromBody](template: String)(
      handler: (A, Body) => org.apache.pekko.stream.scaladsl.Source[String, ?]
  ): Unit =
    addStream("POST", template, 1, needsBody = true)((args, body) =>
      handler(pathArg[A](args, 0, template), bodyArg[Body](body))
    )

  private def pathArg[A](args: Vector[String], index: Int, template: String)(using
      from: FromPath[A]
  ): A =
    from.parse(args(index)) match
      case Right(value) => value
      case Left(reason) => throw HttpProblem.badRequest(s"$reason in '$template'")

  private def bodyArg[B](bytes: Array[Byte])(using from: FromBody[B]): B =
    from.read(bytes) match
      case Right(value) => value
      case Left(reason) => throw HttpProblem.badRequest(reason)

  /** Routes `GET $$prefix$$template` to `handler`. */
  protected def get[R: ToResponse](template: String)(
      handler: () => R
  ): Unit =
    add("GET", template, 0, needsBody = false)((_, _) => handler())

  /** Routes `GET $$prefix$$template` to `handler`. */
  protected def get[A: FromPath, R: ToResponse](template: String)(
      handler: (A) => R
  ): Unit =
    add("GET", template, 1, needsBody = false)((args, _) => handler(pathArg[A](args, 0, template)))

  /** Routes `GET $$prefix$$template` to `handler`. */
  protected def get[A: FromPath, B: FromPath, R: ToResponse](template: String)(
      handler: (A, B) => R
  ): Unit =
    add("GET", template, 2, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template))
    )

  /** Routes `DELETE $$prefix$$template` to `handler`. */
  protected def delete[R: ToResponse](template: String)(
      handler: () => R
  ): Unit =
    add("DELETE", template, 0, needsBody = false)((_, _) => handler())

  /** Routes `DELETE $$prefix$$template` to `handler`. */
  protected def delete[A: FromPath, R: ToResponse](template: String)(
      handler: (A) => R
  ): Unit =
    add("DELETE", template, 1, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template))
    )

  /** Routes `DELETE $$prefix$$template` to `handler`. */
  protected def delete[A: FromPath, B: FromPath, R: ToResponse](template: String)(
      handler: (A, B) => R
  ): Unit =
    add("DELETE", template, 2, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template))
    )

  /** Routes `POST $$prefix$$template` to `handler`. */
  protected def post[R: ToResponse](template: String)(
      handler: () => R
  ): Unit =
    add("POST", template, 0, needsBody = false)((_, _) => handler())

  /** Routes `POST $$prefix$$template` to `handler`. */
  protected def post[A: FromPath, R: ToResponse](template: String)(
      handler: (A) => R
  ): Unit =
    add("POST", template, 1, needsBody = false)((args, _) => handler(pathArg[A](args, 0, template)))

  /** Routes `POST $$prefix$$template` to `handler`. */
  protected def post[A: FromPath, B: FromPath, R: ToResponse](template: String)(
      handler: (A, B) => R
  ): Unit =
    add("POST", template, 2, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template))
    )

  /** Routes `PUT $$prefix$$template` to `handler`. */
  protected def put[R: ToResponse](template: String)(
      handler: () => R
  ): Unit =
    add("PUT", template, 0, needsBody = false)((_, _) => handler())

  /** Routes `PUT $$prefix$$template` to `handler`. */
  protected def put[A: FromPath, R: ToResponse](template: String)(
      handler: (A) => R
  ): Unit =
    add("PUT", template, 1, needsBody = false)((args, _) => handler(pathArg[A](args, 0, template)))

  /** Routes `PUT $$prefix$$template` to `handler`. */
  protected def put[A: FromPath, B: FromPath, R: ToResponse](template: String)(
      handler: (A, B) => R
  ): Unit =
    add("PUT", template, 2, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template))
    )

  /** Routes `PATCH $$prefix$$template` to `handler`. */
  protected def patch[R: ToResponse](template: String)(
      handler: () => R
  ): Unit =
    add("PATCH", template, 0, needsBody = false)((_, _) => handler())

  /** Routes `PATCH $$prefix$$template` to `handler`. */
  protected def patch[A: FromPath, R: ToResponse](template: String)(
      handler: (A) => R
  ): Unit =
    add("PATCH", template, 1, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template))
    )

  /** Routes `PATCH $$prefix$$template` to `handler`. */
  protected def patch[A: FromPath, B: FromPath, R: ToResponse](template: String)(
      handler: (A, B) => R
  ): Unit =
    add("PATCH", template, 2, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template))
    )

  /** Routes `POST $$prefix$$template` with a decoded request body. */
  protected def postBody[Body: FromBody, R: ToResponse](template: String)(
      handler: (Body) => R
  ): Unit =
    add("POST", template, 0, needsBody = true)((_, body) => handler(bodyArg[Body](body)))

  /** Routes `POST $$prefix$$template` with a decoded request body. */
  protected def postBody[A: FromPath, Body: FromBody, R: ToResponse](template: String)(
      handler: (A, Body) => R
  ): Unit =
    add("POST", template, 1, needsBody = true)((args, body) =>
      handler(pathArg[A](args, 0, template), bodyArg[Body](body))
    )

  /** Routes `POST $$prefix$$template` with a decoded request body. */
  protected def postBody[A: FromPath, B: FromPath, Body: FromBody, R: ToResponse](template: String)(
      handler: (A, B, Body) => R
  ): Unit =
    add("POST", template, 2, needsBody = true)((args, body) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template), bodyArg[Body](body))
    )

  /** Routes `PUT $$prefix$$template` with a decoded request body. */
  protected def putBody[Body: FromBody, R: ToResponse](template: String)(
      handler: (Body) => R
  ): Unit =
    add("PUT", template, 0, needsBody = true)((_, body) => handler(bodyArg[Body](body)))

  /** Routes `PUT $$prefix$$template` with a decoded request body. */
  protected def putBody[A: FromPath, Body: FromBody, R: ToResponse](template: String)(
      handler: (A, Body) => R
  ): Unit =
    add("PUT", template, 1, needsBody = true)((args, body) =>
      handler(pathArg[A](args, 0, template), bodyArg[Body](body))
    )

  /** Routes `PUT $$prefix$$template` with a decoded request body. */
  protected def putBody[A: FromPath, B: FromPath, Body: FromBody, R: ToResponse](template: String)(
      handler: (A, B, Body) => R
  ): Unit =
    add("PUT", template, 2, needsBody = true)((args, body) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template), bodyArg[Body](body))
    )

  /** Routes `PATCH $$prefix$$template` with a decoded request body. */
  protected def patchBody[Body: FromBody, R: ToResponse](template: String)(
      handler: (Body) => R
  ): Unit =
    add("PATCH", template, 0, needsBody = true)((_, body) => handler(bodyArg[Body](body)))

  /** Routes `PATCH $$prefix$$template` with a decoded request body. */
  protected def patchBody[A: FromPath, Body: FromBody, R: ToResponse](template: String)(
      handler: (A, Body) => R
  ): Unit =
    add("PATCH", template, 1, needsBody = true)((args, body) =>
      handler(pathArg[A](args, 0, template), bodyArg[Body](body))
    )

  /** Routes `PATCH $$prefix$$template` with a decoded request body. */
  protected def patchBody[A: FromPath, B: FromPath, Body: FromBody, R: ToResponse](
      template: String
  )(
      handler: (A, B, Body) => R
  ): Unit =
    add("PATCH", template, 2, needsBody = true)((args, body) =>
      handler(pathArg[A](args, 0, template), pathArg[B](args, 1, template), bodyArg[Body](body))
    )
