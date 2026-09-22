package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.auth.{
  AuthConfig,
  Principals,
  TokenVerifier,
  Verification
}
import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, RequestContext}

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
