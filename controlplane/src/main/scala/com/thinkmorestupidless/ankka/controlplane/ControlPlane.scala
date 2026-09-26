package com.thinkmorestupidless.ankka.controlplane

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.*
import com.thinkmorestupidless.ankka.controlplane.auth.{AuthConfig, DeployTokenIndex, TokenVerifier}
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  RegistryWriter,
  ServiceProjector
}
import com.thinkmorestupidless.ankka.controlplane.tenancy.OrganizationPolicy
import com.thinkmorestupidless.ankka.http.{Acl, HttpServer}
import com.thinkmorestupidless.ankka.runtime.{Ankka, ProjectionRuntime, ServiceBuilder}
import com.thinkmorestupidless.ankka.core.ComponentDescriptor

/**
 * The control plane, assembled from ankka's own components.
 *
 * There is nothing privileged here: tenancy is three event sourced entities, listings are three
 * views, the API is three endpoints. The thing that manages ankka services is itself an ankka
 * service, which means it inherits sharding, replay and projections rather than reimplementing them
 * — and that a bug in the platform shows up in the tool used to operate it, where it is hard to
 * ignore.
 */
object ControlPlane:

  /**
   * Every component the control plane hosts, in one place.
   *
   * The projection trigger is not here: it needs the projector, which is created when the service
   * is assembled. See [[componentsWith]].
   */
  val components: Seq[ComponentDescriptor] = Seq(
    OrganizationEntity.descriptor,
    ProjectEntity.descriptor,
    ServiceEntity.descriptor,
    OrganizationRows.descriptor,
    ProjectRows.descriptor,
    ServiceRows.descriptor,
    // Feature 013. The entity is what every node replays into its own DeployTokenIndex so its acl
    // can verify a token without touching the database; the view is only for listing them.
    DeployTokenEntity.descriptor,
    DeployTokenRows.descriptor
  )

  /** The full inventory, including the consumer that projects on a desired-state change. */
  def componentsWith(projector: ServiceProjector): Seq[ComponentDescriptor] =
    components :+ ProjectionTrigger.companion(projector).descriptor :+
      SuspensionTrigger.companion(projector).descriptor

  /**
   * The endpoints, all but one sharing the ACL. The service endpoint also needs the deployment
   * configuration — the base domain under which exposed services answer. With an `auth`
   * configuration, `GET /auth` advertises the issuer to the CLI, unauthenticated by design. The
   * organization endpoint takes the installation's creation `policy` (feature 011); the default is
   * open, which is every installation before that feature.
   */
  def endpoints(
      acl: Acl,
      deploy: DeployConfig = DeployConfig.default,
      auth: Option[AuthConfig] = None,
      policy: OrganizationPolicy = OrganizationPolicy.default,
      /**
       * The clock every endpoint stamps its commands with.
       *
       * Each endpoint has taken one since feature 008; this hands them all the *same* one, so a
       * suite that needs to see a deadline pass — a deploy token's expiry — advances one clock
       * rather than constructing the endpoints itself and diverging from what ships.
       */
      clock: java.time.Clock = java.time.Clock.systemUTC(),
      /** This node's token index, so a revoke evicts here before the journal carries it. */
      tokens: Option[DeployTokenIndex] = None,
      /**
       * Where a project's registry credential is written (feature 013).
       *
       * The projector implements it, because the cluster client is built when the projector starts
       * and a project's namespace is named from the same configuration. `None` — a control plane
       * with no cluster behind it — makes the registry routes answer unavailable.
       */
      registry: Option[RegistryWriter] = None
  ): Seq[
    com.thinkmorestupidless.ankka.http.EndpointClients => com.thinkmorestupidless.ankka.http.HttpEndpoint
  ] =
    Seq[
      com.thinkmorestupidless.ankka.http.EndpointClients => com.thinkmorestupidless.ankka.http.HttpEndpoint
    ](
      clients => OrganizationEndpoint(clients, acl, policy, clock, tokens),
      clients => ProjectEndpoint(clients, acl, clock, registry),
      // `logs` keeps its own default rather than being built from `deploy`: that is the behaviour
      // this call has always had, and changing it here would be an unrelated fix smuggled in.
      clients => ServiceEndpoint(clients, acl, deploy, clock = clock),
      clients => WhoamiEndpoint(clients, acl, clock)
    ) ++ auth.map(config =>
      (_: com.thinkmorestupidless.ankka.http.EndpointClients) => AuthDiscoveryEndpoint(config)
    )

  /**
   * A service definition, ready to `start()`.
   *
   * `ProjectionRuntime` is not optional here: three of the six components are views, and without it
   * every listing would stay permanently empty while every write succeeded.
   */
  def builder(
      acl: Acl,
      interface: Option[String] = None,
      port: Option[Int] = None,
      config: Config = ConfigFactory.load(),
      auth: Option[AuthConfig] = None,
      /**
       * The index the ACL verifies deploy tokens against (feature 013).
       *
       * Passed in rather than created here because `aclFrom` builds the ACL *and* the index
       * together — the ACL is useless without the index that answers it, and an index nothing
       * registered would never be filled. `aclWithTokens` returns both; this takes the one it must
       * also register as an extension so it replays on start and reports readiness.
       */
      tokens: Option[DeployTokenIndex] = None
  ): ServiceBuilder =
    val deploy = DeployConfig.from(config)
    val policy = OrganizationPolicy.from(config)
    // Before the endpoints, because one of them writes through it: `PUT /projects/{id}/registry`
    // hands a credential to the cluster, and the projector is what holds the client that can.
    val projector = ServiceProjector(deploy)
    val server = (interface, port) match
      case (Some(host), Some(bindPort)) =>
        HttpServer.at(host, bindPort)(
          endpoints(acl, deploy, auth, policy, tokens = tokens, registry = Some(projector))*
        )
      case _ =>
        HttpServer.of(
          endpoints(acl, deploy, auth, policy, tokens = tokens, registry = Some(projector))*
        )
    val base = Ankka.service
      .registerAll(componentsWith(projector))
      .withExtension(ProjectionRuntime())
      .withExtension(projector)
      .withExtension(server)
    tokens.fold(base)(base.withExtension)

  /**
   * The ACL from configuration: verified OpenID Connect tokens from the configured issuer.
   *
   * Refuses to start without an issuer. A control plane that comes up unauthenticated because a
   * value was missing is worse than one that refuses to come up. The issuer being *unreachable* is
   * different — that is a 503 on each request, not a process that will not start (FR-005).
   */
  def aclFrom(config: Config): Acl = aclFor(AuthConfig.from(config))

  def aclFor(auth: AuthConfig): Acl = ControlPlaneAcl.oidc(TokenVerifier.remote(auth), auth)

  /**
   * The ACL a deployed control plane runs: identity-provider tokens *and* deploy tokens.
   *
   * Returns the index as well as the ACL because the two are one thing — the ACL answers from the
   * index, and the index only fills if it is registered as an extension. Handing back a bare ACL
   * would make it possible to build a control plane whose deploy tokens never work and whose
   * readiness never explains why.
   */
  def aclWithTokens(auth: AuthConfig): (Acl, DeployTokenIndex) =
    val index = new DeployTokenIndex()
    (ControlPlaneAcl.composite(index, aclFor(auth), auth), index)
