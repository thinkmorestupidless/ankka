package com.thinkmorestupidless.ankka.proxy.core

/**
 * Where a call at the calling address goes (feature 021).
 *
 * The first path segment names the service: `<service>` for one of the proxy's own project, or
 * `<service>.<project>` for another project's. Names are DNS labels, so a dot cannot occur in one
 * and the two forms cannot be confused; the order is the in-cluster address's own. The rest of the
 * path, with the query, is what the service receives.
 */
object CallingAddress:

  /** One call's destination, and the path the service receives, leading `/` and query kept. */
  final case class Target(project: String, service: String, rest: String)

  private val Label = "[a-z]([-a-z0-9]{0,61}[a-z0-9])?".r

  /**
   * @param target
   *   the request target as it arrived at the calling address: the raw path and query
   * @param callingUrl
   *   the calling address as the process was given it, for the refusal's words
   */
  def parse(target: String, ownProject: String, callingUrl: String): Either[String, Target] =
    val (path, query) = target.indexOf('?') match
      case -1 => (target, "")
      case at => (target.substring(0, at), target.substring(at))
    val trimmed = path.stripPrefix("/")
    val (first, after) = trimmed.indexOf('/') match
      case -1 => (trimmed, "")
      case at => (trimmed.substring(0, at), trimmed.substring(at))
    val rest      = (if after.isEmpty then "/" else after) + query
    val noService = Left(s"a call names a service: $callingUrl/<service>/<path>")
    if first.isEmpty then noService
    else
      first.split('.') match
        case Array(service) if Label.matches(service) => Right(Target(ownProject, service, rest))
        case Array(service, project) if Label.matches(service) && Label.matches(project) =>
          Right(Target(project, service, rest))
        case _ =>
          Left(
            s"'$first' does not name a service: a call is $callingUrl/<service>/<path> or " +
              s"$callingUrl/<service>.<project>/<path>"
          )

/** Where a service answers, and so where a call to it or a request under its mount is sent. */
trait Locator:
  def locate(project: String, service: String): Option[Located]

/** A located service: the base its requests are sent to, without a path. */
final case class Located(uri: java.net.URI)
