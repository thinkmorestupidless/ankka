package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.auth.{
  AuthConfig,
  DeployTokens,
  Principals,
  TokenVerifier,
  Verification
}
import com.thinkmorestupidless.ankka.controlplane.domain.DeployToken
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal, RequestContext}

/**
 * Who may reach the control plane: anyone presenting a valid access token from the installation's
 * own identity provider (feature 008).
 *
 * Authentication only. Whether that person may touch a given organization is the endpoints'
 * decision, made from the `Organization` entity's own state — the identity provider never learns
 * what an organization is, and this process never holds a credential able to ask it anything.
 *
 * The shared bearer token that stood here before is gone, with no compatibility mode: a mode that
 * accepted an unverified secret would be the thing this feature removes.
 */
object ControlPlaneAcl:

  private val Scheme = "Bearer "

  /**
   * Two kinds of credential, one door: a deploy token, else an identity-provider token.
   *
   * Which is which is decided by *inspection*, never by trying one and falling back — `ankka_` is a
   * deploy token's prefix and nothing else's, and `TokenVerifier` refuses anything that is not
   * dot-dot-shaped before it parses, so a guess would produce a misleading message. A bearer that
   * claims to be a deploy token and is malformed is refused as one rather than handed to the OIDC
   * verifier, so the caller is told what was actually wrong.
   *
   * The token path performs no I/O. That is the whole reason `DeployTokenIndex` exists: this
   * function runs on the server's dispatcher, not on a handler's virtual thread.
   */
  def composite(
      index: com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex,
      oidc: Acl,
      config: AuthConfig
  ): Acl =
    val realm = s"""realm="${config.realmHint}""""
    val decideOidc = oidc match
      case Acl.Authenticate(decide) => decide
      case _ =>
        throw IllegalArgumentException(
          "the control plane's fallback acl must authenticate; got " + oidc
        )

    Acl.Authenticate { context =>
      presented(context) match
        case Some(bearer) if DeployTokens.looksLikeOne(bearer) =>
          admitToken(index, bearer, realm)
        case _ => decideOidc(context)
    }

  private def admitToken(
      index: com.thinkmorestupidless.ankka.controlplane.auth.DeployTokenIndex,
      bearer: String,
      realm: String
  ): AuthDecision =
    def rejected(why: String) =
      AuthDecision.Unauthenticated(s"""$realm, error="invalid_token", error_description="$why"""")

    // Before anything else: a node that has not replayed the token journal does not know whether
    // this token is good, and must say so rather than refuse a perfectly valid credential. In
    // practice the platform does not route here — readiness is false until the replay finishes.
    if !index.ready then
      AuthDecision.Unavailable("deploy tokens cannot be verified yet on this node")
    else
      DeployTokens.parse(bearer) match
        case None => rejected("not a deploy token")
        case Some((id, secret)) =>
          index.lookup(id) match
            // Unknown, revoked and expired are one answer on purpose. Distinguishing them would
            // tell a caller holding a guessed id that the id exists.
            case None => rejected("deploy token not recognised")
            case Some(entry) if !DeployTokens.matches(entry.digest, secret) =>
              rejected("deploy token not recognised")
            case Some(entry) =>
              index.touch(id)
              AuthDecision.Allow(
                Principal(
                  subject = DeployToken.subjectOf(id),
                  name = Some(entry.label),
                  claims = Map(
                    "kind"         → "deploy-token",
                    "organization" → entry.organizationId
                  )
                )
              )

  def oidc(verifier: TokenVerifier, config: AuthConfig): Acl =
    val realm = s"""realm="${config.realmHint}""""
    Acl.Authenticate { context =>
      presented(context) match
        case None => AuthDecision.Unauthenticated(realm)
        case Some(token) =>
          verifier.verify(token) match
            case Verification.Verified(claims) => AuthDecision.Allow(Principals.from(claims))
            case Verification.Rejected(reason) =>
              AuthDecision.Unauthenticated(
                s"""$realm, error="invalid_token", error_description="${quoted(reason)}""""
              )
            case Verification.Unavailable(reason) =>
              AuthDecision.Unavailable(
                s"the control plane cannot verify callers right now: $reason"
              )
    }

  private def presented(context: RequestContext): Option[String] =
    context
      .header("authorization")
      .map(_.trim)
      .collect { case value if value.startsWith(Scheme) => value.substring(Scheme.length).trim }
      .filter(_.nonEmpty)

  /** A challenge parameter is a quoted-string: no quotes, no newlines, and never the token. */
  private def quoted(reason: String): String =
    reason.replace("\"", "'").replace("\n", " ").take(200)
