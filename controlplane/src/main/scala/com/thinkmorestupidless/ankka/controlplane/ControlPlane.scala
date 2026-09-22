package com.thinkmorestupidless.ankka.controlplane

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.thinkmorestupidless.ankka.controlplane.api.*
import com.thinkmorestupidless.ankka.controlplane.application.*
import com.thinkmorestupidless.ankka.controlplane.auth.{AuthConfig, TokenVerifier}
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjector}
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
    ServiceRows.descriptor
  )

  /** The full inventory, including the consumer that projects on a desired-state change. */
  def componentsWith(projector: ServiceProjector): Seq[ComponentDescriptor] =
    components :+ ProjectionTrigger.companion(projector).descriptor :+
      SuspensionTrigger.companion(projector).descriptor

  /**
   * The endpoints, all but one sharing the ACL. The service endpoint also needs the deployment
   * configuration — the base domain under which exposed services answer. With an `auth`
   * configuration, `GET /auth` advertises the issuer to the CLI, unauthenticated by design.
   */
  def endpoints(
      acl: Acl,
      deploy: DeployConfig = DeployConfig.default,
      auth: Option[AuthConfig] = None
  ): Seq[
    com.thinkmorestupidless.ankka.http.EndpointClients => com.thinkmorestupidless.ankka.http.HttpEndpoint
  ] =
    Seq[
      com.thinkmorestupidless.ankka.http.EndpointClients => com.thinkmorestupidless.ankka.http.HttpEndpoint
    ](
      clients => OrganizationEndpoint(clients, acl),
      clients => ProjectEndpoint(clients, acl),
      clients => ServiceEndpoint(clients, acl, deploy),
      clients => WhoamiEndpoint(clients, acl)
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
      auth: Option[AuthConfig] = None
  ): ServiceBuilder =
    val deploy = DeployConfig.from(config)
    val server = (interface, port) match
      case (Some(host), Some(bindPort)) =>
        HttpServer.at(host, bindPort)(endpoints(acl, deploy, auth)*)
      case _ => HttpServer.of(endpoints(acl, deploy, auth)*)
    val projector = ServiceProjector(deploy)
    Ankka.service
      .registerAll(componentsWith(projector))
      .withExtension(ProjectionRuntime())
      .withExtension(projector)
      .withExtension(server)

  /**
   * The ACL from configuration: verified OpenID Connect tokens from the configured issuer.
   *
   * Refuses to start without an issuer. A control plane that comes up unauthenticated because a
   * value was missing is worse than one that refuses to come up. The issuer being *unreachable* is
   * different — that is a 503 on each request, not a process that will not start (FR-005).
   */
  def aclFrom(config: Config): Acl = aclFor(AuthConfig.from(config))

  def aclFor(auth: AuthConfig): Acl = ControlPlaneAcl.oidc(TokenVerifier.remote(auth), auth)
