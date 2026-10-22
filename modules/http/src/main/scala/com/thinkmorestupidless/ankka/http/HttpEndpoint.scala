package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}

import scala.collection.mutable

/** A problem to report back to the caller. */
final case class HttpProblem(
    status: Int,
    message: String,
    /** What a caller reads without parsing the message: a failed workflow's step and reason. */
    details: Map[String, String] = Map.empty
) extends RuntimeException(message):
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
      // Failed Dependency: the call was answered, and what it waited on failed. Told apart from
      // every refusal (400–409) and from the call itself breaking (500, 503, 504).
      case ErrorCode.WorkflowFailed => 424
      // Misdirected Request: this installation's copy cannot answer; the message names the home.
      case ErrorCode.NotHome => 421
    HttpProblem(status, error.message, error.details)

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
    claims: Map[String, String] = Map.empty,
    /**
     * The configured name of the issuer that verified the token, when `ankka-auth-oidc` built this
     * principal; `None` when another `Authenticate` did.
     */
    issuer: Option[String] = None
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

  /**
   * Only the callers named: the internet, a named service, any service in this project, this
   * service itself. Admits a request whose caller any one of them matches.
   *
   * Trustworthy because in a cluster the caller is read from the client certificate the platform
   * issued, never from anything the request says about itself. Outside a cluster there is no
   * certificate, every caller is `Caller.Local`, and this admits it — the service logs once at
   * startup that callers are not enforced there. Build one with `Acl.allowCallers`, which cannot be
   * empty.
   */
  case AllowCallers(matchers: Vector[CallerMatcher])

  /**
   * Any of several ACLs (feature 054): a request is admitted when one of them admits it, the first
   * that does in the order given, so one endpoint can admit a workload by its certificate, a person
   * by a token and an assistant by a client key. Build one with `Acl.anyOf`, which cannot be empty.
   *
   * When none admits, the answer is the one a caller can act on: "cannot tell right now" (503) if
   * any member could not decide, else the challenge of the first member that asked for a credential
   * — the first that says what was wrong with the one presented, when one does — else the first
   * refusal.
   */
  case AnyOf(acls: Vector[Acl])

object Acl:
  def allowCallers(first: CallerMatcher, rest: CallerMatcher*): Acl =
    AllowCallers(first +: rest.toVector)

  /** An ACL admitting a request any one of `first` and `rest` admits; see `AnyOf`. */
  def anyOf(first: Acl, rest: Acl*): Acl = AnyOf(first +: rest.toVector)

  /** Whether `acl` admits granted callers: what makes a route grantable (feature 040). */
  def namesGranted(acl: Acl): Boolean = acl match
    case AllowCallers(matchers) => matchers.contains(CallerMatcher.Granted)
    case AnyOf(acls)            => acls.exists(namesGranted)
    case _                      => false

  /** Every ACL in `acl`'s tree, itself first: what a server reads authenticators from. */
  private[ankka] def flatten(acl: Acl): Vector[Acl] = acl match
    case AnyOf(acls) => acl +: acls.flatMap(flatten)
    case other       => Vector(other)

  /** The authenticators in `acl`'s tree, in order. */
  private[ankka] def authenticators(acl: Acl): Vector[RequestContext => AuthDecision] =
    flatten(acl).collect { case Authenticate(decide) => decide }

/**
 * An authenticator that admits tokens an issuer signed (feature 054), and says which issuers: what
 * an MCP endpoint's resource metadata names as the places a client may sign in. `Oidc.authenticate`
 * answers one.
 */
trait TokenAuthenticator extends (RequestContext => AuthDecision):
  /** The issuers' URLs, in the order the service lists them. */
  def authorizationServers: Vector[String]

/**
 * An authenticator that needs the running service before it can decide (feature 054): a client key
 * is looked up in an index an extension keeps. The server binds every one it finds in its
 * endpoints' ACLs when it starts, and a `Left` fails the start with that sentence. Until it is
 * bound, it decides nothing but "unavailable".
 */
trait NeedsService:
  def bind(service: com.thinkmorestupidless.ankka.runtime.AnkkaService): Either[String, Unit]

/**
 * What an endpoint is mounted beside (feature 054): the service's components, every endpoint the
 * server serves, and the service when there is one. What an endpoint that refers to others — an MCP
 * endpoint's tools — resolves its references against, and refuses a start with.
 */
private[ankka] final case class Mounting(
    registry: com.thinkmorestupidless.ankka.core.ComponentRegistry,
    endpoints: Vector[HttpEndpoint],
    service: Option[com.thinkmorestupidless.ankka.runtime.AnkkaService]
)

/**
 * An endpoint that says itself what it serves (feature 054), instead of the server reading its
 * routes: an MCP endpoint is three routes on the wire and one record per tool to every reader.
 */
private[ankka] trait ServesRoutes:
  def served: Vector[com.thinkmorestupidless.ankka.runtime.ServedRoute]

private[ankka] final case class EncodedResponse(
    status: Int,
    contentType: String,
    body: Array[Byte],
    headers: Vector[(String, String)] = Vector.empty,
    /** A body written as it is read (feature 055), in place of `body`. */
    streamed: Option[Streamed] = None
)

private[ankka] final case class Route(
    method: String,
    template: PathTemplate,
    needsBody: Boolean,
    run: (Vector[String], Array[Byte]) => EncodedResponse,
    /** Declared by `withAcl`; `None` means the endpoint's. */
    acl: Option[Acl] = None,
    /**
     * A route whose body is read as it arrives (`postStream`, `putStream`; feature 055): the server
     * hands this the body as a stream instead of calling `run` with it whole.
     */
    streamedBody: Option[(Vector[String], StreamedBody) => EncodedResponse] = None
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
    run: (Vector[String], Array[Byte]) => org.apache.pekko.stream.scaladsl.Source[SseEvent, ?],
    /** Declared by `withAcl`; `None` means the endpoint's. */
    acl: Option[Acl] = None
):
  def describe: String = s"$method ${template.render} (SSE)"

/**
 * A route answered by opening a socket rather than with one response.
 *
 * Always `GET`, never with a body: the opening request is an upgrade. `run` takes the path
 * arguments and answers the handler, so a path argument that does not parse is a 400 to the opening
 * request — before any socket exists — and not a socket that closes at once.
 */
private[ankka] final case class SocketRoute(
    template: PathTemplate,
    run: Vector[String] => Socket => Unit,
    /** Declared by `withAcl`; `None` means the endpoint's. */
    acl: Option[Acl] = None
):
  def method: String   = "GET"
  def describe: String = s"SOCKET ${template.render}"

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

  /** Which workload sent this request; see `Caller`. Always present. */
  protected def caller: Caller = request.caller

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
  private val collectedSockets = mutable.ListBuffer.empty[SocketRoute]

  // Routes are declared in the constructor body, which is single-threaded, so a var scoped
  // around the declarations is all `withAcl` needs.
  private var scopedAcl: Option[Acl] = None

  private[ankka] def routes: Vector[Route]             = collected.toVector
  private[ankka] def streamRoutes: Vector[StreamRoute] = collectedStreams.toVector
  private[ankka] def socketRoutes: Vector[SocketRoute] = collectedSockets.toVector

  /**
   * Routes at a path of their own rather than under this endpoint's prefix (feature 054): their
   * templates are whole paths. An MCP endpoint at `/mcp` publishes its resource metadata at
   * `/.well-known/oauth-protected-resource/mcp`, which no prefix of its could reach. Matched before
   * any prefix, after `/_ankka/health`, and admitted by the route's ACL or else the endpoint's.
   */
  private[ankka] def rootRoutes: Vector[Route] = Vector.empty

  /**
   * ACLs this endpoint decides that belong to no route (feature 054): an MCP endpoint's tools'. The
   * server binds the authenticators in them at start as it binds the routes'.
   */
  private[ankka] def innerAcls: Vector[Acl] = Vector.empty

  /**
   * What is wrong with this endpoint where it is mounted (feature 054), each a sentence that fails
   * the start. Asked once every endpoint is built, so an endpoint can refer to the service's
   * components and its sibling endpoints.
   */
  private[ankka] def problems(@scala.annotation.unused mounting: Mounting): Vector[String] =
    Vector.empty

  /**
   * The challenge a `401` from this endpoint carries, given the one its ACL answered (feature 054):
   * an MCP endpoint that admits tokens names its resource metadata here. As it was, by default.
   */
  private[ankka] def challenge(
      @scala.annotation.unused request: RequestContext,
      answered: String
  ): String = answered

  private val prefixSegments: Vector[String] =
    prefix.split('/').iterator.filter(_.nonEmpty).toVector

  private[ankka] def prefixPath: Vector[String] = prefixSegments

  /**
   * Whether this is a process's own directory store, served at `/_ankka/storage` (feature 055): the
   * one endpoint admitted under the runtime's prefix, and only from the sidecar, for a Python or
   * TypeScript service on a developer's machine. No service's endpoint can say so.
   */
  private[ankka] def servesReservedStorage: Boolean = false

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
      (args, body) => encode(run(args, body)),
      scopedAcl
    )

  /** A handler's value as the server answers it: whole, or as a stream the server writes. */
  private def encode[R](value: R)(using response: ToResponse[R]): EncodedResponse =
    // `Bytes` and `Streamed` name their own content type per value; every other body type has one
    // per type.
    value match
      case s: Streamed =>
        EncodedResponse(200, s.contentType, Array.emptyByteArray, Vector.empty, Some(s))
      case Respond(s: Streamed, status, headers) =>
        EncodedResponse(status, s.contentType, Array.emptyByteArray, headers, Some(s))
      case _ =>
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

  /**
   * Records a route whose body is read as it arrives (feature 055). Its handler is given a
   * `StreamedBody` and may answer anything a route answers, a `Streamed` body included.
   */
  private def addStreamedBody[R](method: String, rawTemplate: String, arity: Int)(
      run: (Vector[String], StreamedBody) => R
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
      needsBody = false,
      (_, _) => throw IllegalStateException(s"$method $rawTemplate reads its body as a stream"),
      scopedAcl,
      Some((args, body) => encode(run(args, body)))
    )

  /**
   * A `POST` whose body is read as it arrives rather than whole (feature 055): an upload passed
   * straight to a bucket, say, of any size up to `ankka.http.max-stream-bytes`. A larger declared
   * body is answered `413` and the handler never runs.
   */
  protected def postStream[R: ToResponse](template: String)(handler: StreamedBody => R): Unit =
    addStreamedBody("POST", template, 0)((_, body) => handler(body))

  protected def postStream[A: FromPath, R: ToResponse](template: String)(
      handler: (A, StreamedBody) => R
  ): Unit =
    addStreamedBody("POST", template, 1)((args, body) =>
      handler(pathArg[A](args, 0, template), body)
    )

  /** A `PUT` whose body is read as it arrives, as `postStream`. */
  protected def putStream[R: ToResponse](template: String)(handler: StreamedBody => R): Unit =
    addStreamedBody("PUT", template, 0)((_, body) => handler(body))

  protected def putStream[A: FromPath, R: ToResponse](template: String)(
      handler: (A, StreamedBody) => R
  ): Unit =
    addStreamedBody("PUT", template, 1)((args, body) =>
      handler(pathArg[A](args, 0, template), body)
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
    addEventStream(method, rawTemplate, arity, needsBody)((args, body) =>
      run(args, body).map(SseEvent.text)
    )

  private def addEventStream(method: String, rawTemplate: String, arity: Int, needsBody: Boolean)(
      run: (Vector[String], Array[Byte]) => org.apache.pekko.stream.scaladsl.Source[SseEvent, ?]
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

  /**
   * Records a socket route, checking the handler's arity against the template's placeholders, as
   * `add` does.
   */
  private def addSocket(rawTemplate: String, arity: Int)(
      run: Vector[String] => Socket => Unit
  ): Unit =
    val template = PathTemplate.parse(rawTemplate)
    if template.arity != arity then
      throw IllegalArgumentException(
        s"SOCKET $prefix$rawTemplate declares ${template.arity} path parameter(s) " +
          s"(${template.parameterNames.mkString(", ")}) but its handler takes $arity"
      )
    collectedSockets += SocketRoute(template, run, scopedAcl)

  /**
   * `GET $prefix$template`, answered by opening a socket. The handler runs on a virtual thread for
   * as long as the socket is open; see `Socket`.
   */
  protected def socket(template: String)(handler: Socket => Unit): Unit =
    addSocket(template, 0)(_ => handler)

  /** A socket route with one path parameter. */
  protected def socket[A: FromPath](template: String)(handler: (A, Socket) => Unit): Unit =
    addSocket(template, 1) { args =>
      val a = pathArg[A](args, 0, template)
      socket => handler(a, socket)
    }

  /** A socket route with two path parameters. */
  protected def socket[A: FromPath, B: FromPath](template: String)(
      handler: (A, B, Socket) => Unit
  ): Unit =
    addSocket(template, 2) { args =>
      val a = pathArg[A](args, 0, template)
      val b = pathArg[B](args, 1, template)
      socket => handler(a, b, socket)
    }

  /**
   * `GET $prefix$template`, answered as server-sent events the handler names itself.
   *
   * For a stream that sends something other than text — an agent turn that ends by waiting for
   * approval sends its requests as a named event after its text. A separate name from `sse`, so a
   * handler's lambda never has to say which of the two element types it returns.
   */
  protected def sseEvents[A: FromPath](template: String)(
      handler: A => org.apache.pekko.stream.scaladsl.Source[SseEvent, ?]
  ): Unit =
    addEventStream("GET", template, 1, needsBody = false)((args, _) =>
      handler(pathArg[A](args, 0, template))
    )

  /** `GET $prefix$template` with no path parameters, answered as server-sent events it names. */
  protected def sseEvents(template: String)(
      handler: () => org.apache.pekko.stream.scaladsl.Source[SseEvent, ?]
  ): Unit =
    addEventStream("GET", template, 0, needsBody = false)((_, _) => handler())

  /** `POST $prefix$template` with a decoded body, answered as server-sent events it names. */
  protected def sseEventsBody[A: FromPath, Body: FromBody](template: String)(
      handler: (A, Body) => org.apache.pekko.stream.scaladsl.Source[SseEvent, ?]
  ): Unit =
    addEventStream("POST", template, 1, needsBody = true)((args, body) =>
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

  /** Routes `DELETE $$prefix$$template` to `handler`, with three path parameters. */
  protected def delete[A: FromPath, B: FromPath, C: FromPath, R: ToResponse](template: String)(
      handler: (A, B, C) => R
  ): Unit =
    add("DELETE", template, 3, needsBody = false)((args, _) =>
      handler(
        pathArg[A](args, 0, template),
        pathArg[B](args, 1, template),
        pathArg[C](args, 2, template)
      )
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

  /** Routes `PUT $$prefix$$template` to `handler`, with three path parameters. */
  protected def put[A: FromPath, B: FromPath, C: FromPath, R: ToResponse](template: String)(
      handler: (A, B, C) => R
  ): Unit =
    add("PUT", template, 3, needsBody = false)((args, _) =>
      handler(
        pathArg[A](args, 0, template),
        pathArg[B](args, 1, template),
        pathArg[C](args, 2, template)
      )
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
