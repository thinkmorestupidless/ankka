package nakka.controlplane.api

import nakka.http.{Acl, RequestContext}

/**
 * Who may reach the control plane.
 *
 * The default is a shared bearer token read from configuration, because the alternative defaults
 * are both wrong: `AllowAll` on an API that can delete every service in a cluster, or `DenyAll`,
 * which would make the CLI useless out of the box.
 *
 * A shared token is not identity — it cannot tell two operators apart, and it says nothing about
 * which projects a caller may touch. It is deliberately the floor rather than the ceiling: set
 * `nakka.controlplane.auth.token` to something unguessable for a dev cluster, and replace this with
 * mTLS or verified OIDC before anything else uses it.
 */
object ControlPlaneAcl:

  private val Scheme = "Bearer "

  /** Requires `Authorization: Bearer <token>`. */
  def bearer(token: String): Acl =
    require(token.nonEmpty, "control plane bearer token must not be empty")
    Acl.AllowIf(context => presented(context).contains(token))

  private def presented(context: RequestContext): Option[String] =
    context
      .header("authorization")
      .map(_.trim)
      .collect { case value if value.startsWith(Scheme) => value.substring(Scheme.length).trim }
