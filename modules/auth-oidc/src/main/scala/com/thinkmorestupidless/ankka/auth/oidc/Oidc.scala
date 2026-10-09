package com.thinkmorestupidless.ankka.auth.oidc

import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, RequestContext}

/**
 * A service's users' tokens, verified: `val acl: Acl = Oidc.authenticate()`.
 *
 * The issuers come from the environment's named set (see `OidcConfig.fromEnv`). A service whose set
 * is malformed or empty does not get an ACL at all: building one fails, naming every problem, so a
 * service never starts with an authenticated route it cannot serve.
 */
object Oidc:

  private val Scheme = "Bearer "

  /**
   * The ACL for the issuers the environment lists. Throws, naming every problem, when it cannot.
   */
  def authenticate(): Acl = authenticate(sys.env)

  private[oidc] def authenticate(env: Map[String, String]): Acl =
    OidcConfig.fromEnv(env) match
      case Left(problems) =>
        throw IllegalStateException(
          problems.mkString(
            "the issuers this service accepts are misconfigured:\n  - ",
            "\n  - ",
            ""
          )
        )
      case Right(config) => authenticate(config)

  /** The ACL for a configuration built any other way. Throws when it lists no issuer. */
  def authenticate(config: OidcConfig): Acl =
    if config.isEmpty then
      throw IllegalStateException(
        s"no issuer is configured; set ${OidcConfig.IssuersVariable} to the issuers this " +
          "service accepts tokens from"
      )
    val problems = OidcConfig.problems(config)
    if problems.nonEmpty then
      throw IllegalStateException(problems.mkString("the issuers are misconfigured: ", "; ", ""))
    authenticate(verifier(config), config.realm)

  /** The ACL over a verifier already built, which is how a test hands in its own key sources. */
  def authenticate(verifier: OidcVerifier, realm: String): Acl =
    val challenge = s"""realm="$realm""""
    Acl.Authenticate { context =>
      context.caller match
        // A registered machine whose token the server already verified (feature 040): its token
        // is the installation's, not one of these issuers', and proves the principal as well.
        case com.thinkmorestupidless.ankka.http.Caller.Machine(organization, name) =>
          AuthDecision.Allow(Oidc.machinePrincipal(organization, name))
        case _ => fromIssuers(context, verifier, challenge)
    }

  /** The principal a registered machine is, at a route that authenticates. */
  def machinePrincipal(
      organization: String,
      name: String
  ): com.thinkmorestupidless.ankka.http.Principal =
    com.thinkmorestupidless.ankka.http.Principal(
      subject = s"machine:$organization/$name",
      claims = Map("kind" -> "machine", "organization" -> organization),
      issuer = Some("machine")
    )

  private def fromIssuers(context: RequestContext, verifier: OidcVerifier, challenge: String) =
    presented(context) match
      case None => AuthDecision.Unauthenticated(challenge)
      case Some(token) =>
        verifier.verify(token) match
          case Verification.Verified(claims, issuer) =>
            AuthDecision.Allow(Principals.from(claims, issuer))
          case Verification.Rejected(reason) =>
            AuthDecision.Unauthenticated(
              s"""$challenge, error="invalid_token", error_description="${quoted(reason)}""""
            )
          case Verification.Unavailable(reason) =>
            AuthDecision.Unavailable(s"tokens cannot be verified right now: $reason")

  def verifier(config: OidcConfig): OidcVerifier = OidcVerifier.remote(config)

  /** The bearer token on a request, if it carries one. */
  def presented(context: RequestContext): Option[String] =
    context
      .header("authorization")
      .map(_.trim)
      .collect { case value if value.startsWith(Scheme) => value.substring(Scheme.length).trim }
      .filter(_.nonEmpty)

  /** A challenge parameter is a quoted-string: no quotes, no newlines, and never the token. */
  def quoted(reason: String): String =
    reason.replace("\"", "'").replace("\n", " ").take(200)
