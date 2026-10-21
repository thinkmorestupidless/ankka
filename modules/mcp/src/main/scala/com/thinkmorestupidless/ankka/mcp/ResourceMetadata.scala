package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.http.{Acl, Caller, RequestContext, TokenAuthenticator}

/**
 * An MCP endpoint's protected resource metadata (feature 054, research R10, RFC 9728): where a
 * client with no credential may sign in. Published only by an endpoint whose ACL admits tokens, and
 * naming only the issuers that ACL lists, never the platform's own.
 */
private[ankka] object ResourceMetadata:

  /** Where every endpoint's document is: the well-known prefix, then the endpoint's path. */
  val WellKnown: String = "/.well-known/oauth-protected-resource"

  /** The token authenticators in `acl`'s tree, in order: an `anyOf` is walked, nothing else is. */
  def tokenAuthenticators(acl: Acl): Vector[TokenAuthenticator] =
    Option(acl).toVector.flatMap(Acl.flatten).collect {
      case Acl.Authenticate(t: TokenAuthenticator) =>
        t
    }

  /** Whether `acl` admits a token from some issuer. */
  def admitsTokens(acl: Acl): Boolean = tokenAuthenticators(acl).nonEmpty

  /** The issuers' URLs `acl` admits tokens from, each once, in the order the service lists them. */
  def authorizationServers(acl: Acl): Vector[String] =
    tokenAuthenticators(acl).flatMap(_.authorizationServers).distinct

  /** The document's own path for the endpoint at `path`. */
  def documentPath(path: String): String =
    val endpoint = McpEndpoint.normalised(path)
    if endpoint == "/" then WellKnown else s"$WellKnown$endpoint"

  /**
   * The scheme a client reached the endpoint by: `https` when this server serves TLS or the request
   * came through the gateway, which terminates the client's TLS.
   */
  def scheme(context: RequestContext, secure: Boolean): String =
    if secure || context.caller == Caller.Gateway then "https" else "http"

  private def origin(context: RequestContext, secure: Boolean): String =
    s"${scheme(context, secure)}://${context.header("Host").getOrElse("localhost")}"

  /** The endpoint as a client names it: the resource a token is for. */
  def resource(context: RequestContext, path: String, secure: Boolean): String =
    s"${origin(context, secure)}${McpEndpoint.normalised(path)}"

  /** Where the endpoint's document is, as a client names it. */
  def documentUrl(context: RequestContext, path: String, secure: Boolean): String =
    s"${origin(context, secure)}${documentPath(path)}"

  /** The document: the resource, its issuers, and that a token rides in the header. */
  def document(
      context: RequestContext,
      path: String,
      secure: Boolean,
      servers: Vector[String]
  ): Json =
    Json.obj(
      "resource"                 -> Json.str(resource(context, path, secure)),
      "authorization_servers"    -> Json.Arr(servers.map(Json.str)),
      "bearer_methods_supported" -> Json.arr(Json.str("header"))
    )

  /** The challenge the ACL answered, with where the endpoint's document is. */
  def challenge(answered: String, context: RequestContext, path: String, secure: Boolean): String =
    val named = s"""resource_metadata="${documentUrl(context, path, secure)}""""
    if answered.trim.isEmpty then named else s"$answered, $named"
