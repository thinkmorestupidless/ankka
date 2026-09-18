package nakka.controlplane

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import nakka.controlplane.api.*
import nakka.controlplane.application.*
import nakka.controlplane.deploy.{DeployConfig, ServiceProjector}
import nakka.http.{Acl, HttpServer}
import nakka.runtime.{Nakka, ProjectionRuntime, ServiceBuilder}
import nakka.core.ComponentDescriptor

/**
 * The control plane, assembled from nakka's own components.
 *
 * There is nothing privileged here: tenancy is three event sourced entities, listings are three
 * views, the API is three endpoints. The thing that manages nakka services is itself a nakka
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
    components :+ ProjectionTrigger.companion(projector).descriptor

  /**
   * The three endpoints, all sharing one ACL. The service endpoint also needs the deployment
   * configuration — the base domain under which exposed services answer.
   */
  def endpoints(
      acl: Acl,
      deploy: DeployConfig = DeployConfig.default
  ): Seq[nakka.http.EndpointClients => nakka.http.HttpEndpoint] =
    Seq(
      clients => OrganizationEndpoint(clients, acl),
      clients => ProjectEndpoint(clients, acl),
      clients => ServiceEndpoint(clients, acl, deploy)
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
      config: Config = ConfigFactory.load()
  ): ServiceBuilder =
    val deploy = DeployConfig.from(config)
    val server = (interface, port) match
      case (Some(host), Some(bindPort)) => HttpServer.at(host, bindPort)(endpoints(acl, deploy)*)
      case _                            => HttpServer.of(endpoints(acl, deploy)*)
    val projector = ServiceProjector(deploy)
    Nakka.service
      .registerAll(componentsWith(projector))
      .withExtension(ProjectionRuntime())
      .withExtension(projector)
      .withExtension(server)

  /**
   * Reads the bearer token from configuration, refusing to start without one.
   *
   * A control plane that comes up unauthenticated because a value was missing is worse than one
   * that refuses to come up.
   */
  def aclFrom(config: Config): Acl =
    val token = config.getString("nakka.controlplane.auth.token")
    if token.isEmpty then
      throw IllegalStateException(
        "nakka.controlplane.auth.token is not set; set NAKKA_CONTROLPLANE_TOKEN or pass " +
          "an Acl explicitly"
      )
    ControlPlaneAcl.bearer(token)
