package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

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

/**
 * Who a request came from, as an ACL established it.
 *
 * Plain data. `ankka-http` never constructs one and does not know what a token is: an
 * `Acl.Authenticate` decides, and whatever it verified — a signed token, a client certificate — is
 * its business. `subject` is the one field meant to be used as a key; the rest are display claims
 * and can go stale.
 */
final case class Principal(
    subject: String,
    name: Option[String] = None,
    email: Option[String] = None,
    emailVerified: Boolean = false,
    roles: Set[String] = Set.empty,
    claims: Map[String, String] = Map.empty
)

/**
 * What an authenticating ACL answers.
 *
 * Three ways to say no, because a caller needs to be told which: `Unauthenticated` is "log in"
 * (401, with a challenge), `Forbidden` is "you are logged in and may not" (403), and `Unavailable`
 * is "I cannot tell right now" (503) — the verifier's keys could not be fetched, say. Folding those
 * into one boolean is how a CLI ends up printing "forbidden" to someone whose login merely expired.
 */
enum AuthDecision:
  case Allow(principal: Principal)

  /** Rendered as `WWW-Authenticate: Bearer <challenge>`. */
  case Unauthenticated(challenge: String)
  case Forbidden(reason: String)
  case Unavailable(reason: String)

/** What the caller of an endpoint is allowed to do. */
enum Acl:
  /** Nothing gets through. The default posture for anything not explicitly opened up. */
  case DenyAll

  /** Any caller. Correct for a public API; state it deliberately. */
  case AllowAll

  /**
   * A caller-supplied predicate.
   *
   * ankka does not ship a "same service" or "named service" principal, because establishing who the
   * caller actually is needs mTLS or a verified token, and a check against a client-settable header
   * would be security theatre. Plug in a real check here. Every refusal is a 403; when the answer
   * depends on *who* the caller is, use `Authenticate`, which can also say "log in".
   */
  case AllowIf(predicate: RequestContext => Boolean)

  /**
   * A caller-supplied authenticator.
   *
   * On `Allow` the principal is placed on the request context before dispatch, so a handler reads
   * it as `principal` on its own thread — the same `ThreadLocal` rule as the rest of the context:
   * work handed to another thread cannot see it.
   */
  case Authenticate(decide: RequestContext => AuthDecision)

private[ankka] final case class EncodedResponse(
    status: Int,
    contentType: String,
    body: Array[Byte],
    headers: Vector[(String, String)] = Vector.empty
)

private[ankka] final case class Route(
    method: String,
    template: PathTemplate,
    needsBody: Boolean,
    run: (Vector[String], Array[Byte]) => EncodedResponse,
    /** Declared by `withAcl`; `None` means the endpoint's. */
    acl: Option[Acl] = None
):
  def describe: String = s"$method ${template.render}"

/**
 * A route that answers with a stream of text events rather than one response.
 *
 * Kept as a separate route kind rather than a `ToResponse` instance because the server has to treat
 * it differently all the way down: no content length, no buffering, and the connection stays open.
 */
private[ankka] final case class StreamRoute(
    method: String,
    template: PathTemplate,
    needsBody: Boolean,
    run: (Vector[String], Array[Byte]) => org.apache.pekko.stream.scaladsl.Source[String, ?],
    /** Declared by `withAcl`; `None` means the endpoint's. */
    acl: Option[Acl] = None
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
   *
   * Individual routes can say something different with `withAcl`.
   */
  def acl: Acl

  /**
   * Declares routes that answer to `acl` rather than to the endpoint's.
   *
   * A route's ACL *replaces* the endpoint's for that route; it does not add to it. So an endpoint
   * that is `AllowAll` can hold one authenticated route, and one that is `DenyAll` can open a
   * single route, without being split into two endpoints at two prefixes:
   *
   * {{{
   * val acl: Acl = Acl.AllowAll
   *
   * get("/{cartId}") { (cartId: String) => ... }
   *
   * withAcl(Acl.Authenticate(support)) {
   *   delete("/{cartId}") { (cartId: String) => ... }
   * }
   * }}}
   *
   * Scopes nest, and the innermost wins. A path that matches no route of this endpoint is still
   * judged by the endpoint's own ACL, so a closed endpoint does not disclose which of its paths
   * exist by answering 404 for some of them and 403 for the rest.
   */
  protected def withAcl(acl: Acl)(declare: => Unit): Unit =
    val enclosing = scopedAcl
    scopedAcl = Some(acl)
    try declare
    finally scopedAcl = enclosing

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

  /**
   * Who is calling, as the `Acl.Authenticate` that admitted the request established it.
   *
   * Throws when there is none: a route whose ACL does not authenticate has no business asking, and
   * the mistake should fail on the first request in a test rather than hand `None` into a
   * permission check.
   */
  protected def principal: Principal =
    val current = request
    current.principal.getOrElse(
      // Named by route, not by endpoint: under `withAcl` the ACL that admitted this request is
      // not necessarily the endpoint's, and blaming the wrong one sends the reader to edit a
      // line that was never involved.
      throw IllegalStateException(
        s"'${current.method} ${current.path}' asked for a principal, but the acl that admitted it " +
          "does not authenticate callers"
      )
    )

  private val collected        = mutable.ListBuffer.empty[Route]
  private val collectedStreams = mutable.ListBuffer.empty[StreamRoute]

  // Routes are declared in the constructor body, which is single-threaded, so a var scoped
  // around the declarations is all `withAcl` needs.
  private var scopedAcl: Option[Acl] = None

  private[ankka] def routes: Vector[Route]             = collected.toVector
  private[ankka] def streamRoutes: Vector[StreamRoute] = collectedStreams.toVector

  private val prefixSegments: Vector[String] =
    prefix.split('/').iterator.filter(_.nonEmpty).toVector

  private[ankka] def prefixPath: Vector[String] = prefixSegments

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
        // `Bytes` names its own content type per value; every other body type has one per type.
        val contentType = value match
          case Bytes(kind, _)                => kind
          case Respond(Bytes(kind, _), _, _) => kind
          case _                             => response.contentType
        EncodedResponse(
          response.status(value),
          contentType,
          response.write(value),
          response.headers(value)
        )
      ,
      scopedAcl
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
    collectedStreams += StreamRoute(method, template, needsBody, run, scopedAcl)

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
