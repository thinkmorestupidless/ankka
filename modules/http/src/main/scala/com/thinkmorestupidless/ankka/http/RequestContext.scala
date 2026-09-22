package com.thinkmorestupidless.ankka.http

/**
 * The parts of a request a handler did not receive as arguments.
 *
 * Path parameters and the body arrive as typed arguments because they are structural — a route
 * either has them or is not that route. Query parameters and headers are different: they are
 * optional, repeatable, and vary per call, so threading them through the verb signatures would
 * multiply every overload by their arity.
 *
 * Reached through `request` inside a handler. That is ambient rather than passed, which is a
 * deliberate exception to ankka's usual explicitness — and the same shape entities already use for
 * `currentState` and `commandContext`.
 */
trait RequestContext:
  def method: String

  /** The path as received, including the endpoint prefix. */
  def path: String

  def query: QueryParams

  /** All headers, in arrival order. Names compare case-insensitively. */
  def headers: Vector[(String, String)]

  def header(name: String): Option[String] =
    headers.collectFirst { case (key, value) if key.equalsIgnoreCase(name) => value }

  def remoteAddress: Option[String]

  /**
   * The caller, when the endpoint's ACL is an `Acl.Authenticate` that allowed the request. `None`
   * under every other ACL — read it through `HttpEndpoint.principal`, which insists.
   */
  def principal: Option[Principal]

/**
 * A request's query string.
 *
 * `required` fails with a 400 naming the parameter, rather than returning a default. A missing
 * parameter the handler needed is the caller's mistake and they should be told which one — silently
 * substituting a default turns it into a puzzling empty result.
 */
final class QueryParams private[http] (private val entries: Vector[(String, String)]):

  /** The raw value, unparsed. */
  def raw(name: String): Option[String] =
    entries.collectFirst { case (key, value) if key == name => value }

  /** Every raw value for a repeated parameter, in order. */
  def rawAll(name: String): Vector[String] =
    entries.collect { case (key, value) if key == name => value }

  /** Absent parameters give `None`; a present but unparseable one is a 400. */
  def optional[A](name: String)(using from: FromQuery[A]): Option[A] =
    raw(name).map(parse(name, _))

  /** A parameter the handler cannot proceed without. */
  def required[A](name: String)(using from: FromQuery[A]): A =
    raw(name) match
      case Some(value) => parse(name, value)
      case None        => throw HttpProblem.badRequest(s"query parameter '$name' is required")

  /** Every value for a repeated parameter, each parsed. */
  def all[A](name: String)(using from: FromQuery[A]): Vector[A] =
    rawAll(name).map(parse(name, _))

  /**
   * Present with no value counts as `true`, so `?verbose` and `?verbose=true` agree.
   *
   * A flag is the one case where presence alone is the signal, and requiring `=true` would surprise
   * anyone who has used a command line.
   */
  def flag(name: String): Boolean =
    raw(name).exists(value => value.isEmpty || value.equalsIgnoreCase("true"))

  def contains(name: String): Boolean = raw(name).isDefined

  def isEmpty: Boolean = entries.isEmpty

  def toSeq: Seq[(String, String)] = entries

  override def toString: String =
    entries.map((key, value) => s"$key=$value").mkString("QueryParams(", "&", ")")

  private def parse[A](name: String, value: String)(using from: FromQuery[A]): A =
    from.parse(value) match
      case Right(parsed) => parsed
      case Left(reason)  => throw HttpProblem.badRequest(s"query parameter '$name': $reason")

object QueryParams:
  val empty: QueryParams = new QueryParams(Vector.empty)

  private[http] def apply(entries: Vector[(String, String)]): QueryParams =
    new QueryParams(entries)

private[ankka] final case class SimpleRequestContext(
    method: String,
    path: String,
    query: QueryParams,
    headers: Vector[(String, String)],
    remoteAddress: Option[String],
    principal: Option[Principal] = None
) extends RequestContext

/**
 * Makes the current request reachable from inside a handler.
 *
 * A `ThreadLocal` is sound here precisely because ankka runs each handler on its own virtual
 * thread: there is exactly one request per thread, and the value is cleared when the handler
 * returns.
 *
 * The consequence to know about: work a handler hands to *another* thread cannot see the context.
 * Read what you need before fanning out.
 */
private[http] object RequestScope:

  private val current = ThreadLocal[RequestContext]()

  def withContext[A](context: RequestContext)(body: => A): A =
    current.set(context)
    try body
    finally current.remove()

  def currentContext: Option[RequestContext] = Option(current.get())
