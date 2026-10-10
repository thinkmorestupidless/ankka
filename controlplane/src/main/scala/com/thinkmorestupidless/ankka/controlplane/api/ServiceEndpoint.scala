package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{ServiceEntity, ServiceRows}
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  DeployConfig,
  InstanceTopologies,
  PodLogReader,
  PodLogs,
  ObjectStoreKind,
  ServiceProjection,
  TopologyReader
}
import com.thinkmorestupidless.ankka.controlplane.domain.{ApplyService, RollbackService, ServiceKey}
import com.thinkmorestupidless.ankka.controlplane.tenancy.OrganizationUsage
import com.thinkmorestupidless.ankka.core.{CommandError, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.crd.Hostnames
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

import scala.util.control.NonFatal

/**
 * Services, addressed by project and name.
 *
 * The path mirrors the entity key — `/services/{projectId}/{name}` is `projectId/name` — rather
 * than nesting under `/projects`. That is a routing constraint, not a preference: ankka matches an
 * endpoint by prefix, so a second endpoint under `/projects/...` would be shadowed by the one that
 * owns `/projects`.
 *
 * `PUT` is the apply: the request body is the whole desired state, and the response is the status
 * that resulted. Nothing here touches Kubernetes — an apply records intent and returns, and the
 * reconciler closes the gap afterwards.
 */
final class ServiceEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    deploy: DeployConfig = DeployConfig.default,
    logs: PodLogReader = PodLogs(DeployConfig.default.namespacePrefix),
    protected val clock: java.time.Clock = java.time.Clock.systemUTC(),
    topology: TopologyReader = InstanceTopologies(DeployConfig.default.namespacePrefix)
) extends HttpEndpoint("/services")
    with Attributing:

  private val services = clients.viewClient.forView(ServiceRows)
  private val authz = com.thinkmorestupidless.ankka.controlplane.auth.Authorization(clients, clock)
  private val usage = OrganizationUsage(clients)

  /** The caller's standing in the project's organization, as command metadata (feature 008). */
  private def access(projectId: String, write: Boolean) =
    authz.metadata(authz.project(principal, projectId, write))

  get("/{projectId}") { (projectId: String) =>
    authz.project(principal, projectId, write = false)
    services
      .ordered(
        jsonText("projectId") ++ sql" = $projectId",
        order = jsonText("name")
      )
      .map(withHostname)
  }

  get("/{projectId}/{name}") { (projectId: String, name: String) =>
    authz.project(principal, projectId, write = false)
    withUndeclaredTopics(
      withMountStates(withHostname(entity(projectId, name).call(ServiceEntity.get).invoke()))
    )
  }

  /**
   * The topics the service's components read or publish to that its project does not declare
   * (feature 027), from the topology its running instances report: every topic node is a topic a
   * component names. Asked only of a service with an instance ready, and absent when none answered,
   * so "every topic is declared" is never said without having looked.
   */
  private def withUndeclaredTopics(status: ServiceStatus): ServiceStatus =
    if status.readyInstances < 1 then status
    else
      val read =
        try topology.read(status.projectId, status.name).flatMap(_._2)
        catch case scala.util.control.NonFatal(_) => Vector.empty
      if read.isEmpty then status
      else
        // A topic on a declared broker (feature 037) is named `topic:<broker>/<name>` and is that
        // broker's, not one the project would declare.
        val used = read
          .flatMap(_.nodes)
          .filter(n => n.kind == "Topic" && !n.id.stripPrefix("topic:").contains('/'))
          .map(_.id.stripPrefix("topic:"))
          .distinct
          .sorted
        val declared =
          clients.componentClient
            .forEventSourcedEntity(EntityId(status.projectId))
            .call(com.thinkmorestupidless.ankka.controlplane.application.ProjectEntity.topics)
            .invoke()
        status.copy(
          undeclaredTopics = Some(used.filterNot(declared.keySet)),
          topicChecks = Some(TopicChecks.ofService(declared, status.name, read)),
          topicSources = Some(ServiceEndpoint.topicSourcesOf(read)),
          secretStore = read.flatMap(_.secretStore).headOption
        )

  /**
   * The organization is asked for the capacity first (feature 015): a refusal for quota changes
   * nothing, and an apply that then fails puts back what the service counted before — nothing, for
   * a new one. The refusals the entity would make anyway (a descriptor naming another service, an
   * invalid one) are made before asking, so a typo costs the organization no events.
   */
  putBody("/{projectId}/{name}") {
    (projectId: String, name: String, descriptor: ServiceDescriptor) =>
      val authorized = authz.project(principal, projectId, write = true)
      val by         = authz.metadata(authorized)
      if descriptor.name != name then
        throw CommandError(
          s"descriptor names service '${descriptor.name}' but was applied to '$name'",
          ErrorCode.BadRequest
        )
      // What the installation's store cannot give needs the project and the installation, which
      // the descriptor's own rules cannot see; the projection says the same words.
      val problems = descriptor.problems ++
        ServiceProjection.objectStorageProblems(projectId, name, descriptor.service, deploy)
      if problems.nonEmpty then
        throw CommandError(
          problems.mkString("invalid descriptor: ", "; ", ""),
          ErrorCode.BadRequest
        )
      val key       = ServiceKey(projectId, name).id
      val instances = descriptor.service.resources.autoscaling.minInstances
      val previous  = usage.reserveService(authorized.organizationId, key, instances, by)
      try
        withHostname(
          entity(projectId, name)
            .call(ServiceEntity.applyDescriptor)
            .withMetadata(by)
            .invoke(ApplyService(projectId, descriptor))
        )
      catch
        case NonFatal(failure) =>
          usage.undo(s"restore service '$key' to ${previous.fold("nothing")(_.toString)}") {
            usage.recordService(authorized.organizationId, key, previous, by)
          }
          throw failure
  }

  /**
   * A rollback (feature 033): the descriptor of an earlier generation, applied again as a new
   * generation. It is an apply in every check an apply makes, in the same order — the organization
   * asked for the capacity first, the slot given back if the entity then refuses — with one read in
   * front, because the endpoint needs the target's descriptor to check it and reserve for it.
   *
   * The default target is resolved here, once, and the entity is sent the generation it chose. So
   * two people asking at once both resolve to one generation, and the second is refused as already
   * having it, rather than rolling the first one back.
   */
  postBody("/{projectId}/{name}/rollback") {
    (projectId: String, name: String, request: RollbackRequest) =>
      val authorized = authz.project(principal, projectId, write = true)
      val by         = authz.metadata(authorized)
      val target     = entity(projectId, name).call(ServiceEntity.rollbackTarget).invoke(request)
      val problems   = target.descriptor.problems
      if problems.nonEmpty then
        throw CommandError(
          problems.mkString(s"invalid descriptor at generation ${target.generation}: ", "; ", ""),
          ErrorCode.BadRequest
        )
      val key       = ServiceKey(projectId, name).id
      val instances = target.descriptor.service.resources.autoscaling.minInstances
      val previous  = usage.reserveService(authorized.organizationId, key, instances, by)
      try
        val status = entity(projectId, name)
          .call(ServiceEntity.rollback)
          .withMetadata(by)
          .invoke(RollbackService(target.generation))
        RolledBack(target.generation, withHostname(status))
      catch
        case NonFatal(failure) =>
          usage.undo(s"restore service '$key' to ${previous.fold("nothing")(_.toString)}") {
            usage.recordService(authorized.organizationId, key, previous, by)
          }
          throw failure
  }

  /**
   * The descriptor recorded at a generation, exactly as `PUT` accepts one (feature 033). The
   * generation is a query parameter because a route takes two path parameters. Authorized as the
   * history is: a member reads it, and anyone else is told the project does not exist.
   */
  get("/{projectId}/{name}/descriptor") { (projectId: String, name: String) =>
    authz.project(principal, projectId, write = false)
    val generation = query.required[Long]("generation")
    entity(projectId, name).call(ServiceEntity.descriptorAt).invoke(generation)
  }

  post("/{projectId}/{name}/pause") { (projectId: String, name: String) =>
    withHostname(
      entity(projectId, name)
        .call(ServiceEntity.pause)
        .withMetadata(access(projectId, write = true))
        .invoke()
    )
  }

  post("/{projectId}/{name}/resume") { (projectId: String, name: String) =>
    withHostname(
      entity(projectId, name)
        .call(ServiceEntity.resume)
        .withMetadata(access(projectId, write = true))
        .invoke()
    )
  }

  post("/{projectId}/{name}/restart") { (projectId: String, name: String) =>
    withHostname(
      entity(projectId, name)
        .call(ServiceEntity.restart)
        .withMetadata(access(projectId, write = true))
        .invoke()
    )
  }

  /**
   * Issues the service's storage credential again (feature 039): the operator writes a new one,
   * rolls the service onto it, and ends the old one after the rotation grace.
   */
  post("/{projectId}/{name}/storage-credential") { (projectId: String, name: String) =>
    withHostname(
      entity(projectId, name)
        .call(ServiceEntity.reissueStorageCredential)
        .withMetadata(access(projectId, write = true))
        .invoke()
    )
  }

  /**
   * Applies the installation's current bucket settings to the service's bucket in Google Cloud
   * Storage (feature 039): its soft-delete window and its wrapping key.
   */
  post("/{projectId}/{name}/storage/settings") { (projectId: String, name: String) =>
    withHostname(
      entity(projectId, name)
        .call(ServiceEntity.reapplyStorageSettings)
        .withMetadata(access(projectId, write = true))
        .invoke()
    )
  }

  /**
   * Moves the service's bucket from Garage to Google Cloud Storage (feature 039). Refused when the
   * installation keeps new buckets in Garage, since there is nothing to move to; the entity refuses
   * the rest.
   */
  postBody("/{projectId}/{name}/storage/move") {
    (projectId: String, name: String, request: StorageMoveRequest) =>
      val authorized = authz.project(principal, projectId, write = true)
      if deploy.objectStore != ObjectStoreKind.Gcs then
        throw CommandError(
          "the installation keeps new buckets in Garage; there is nowhere to move a bucket to",
          ErrorCode.Conflict
        )
      withHostname(
        entity(projectId, name)
          .call(ServiceEntity.moveStorage)
          .withMetadata(authz.metadata(authorized))
          .invoke(request)
      )
  }

  /**
   * Exposure (feature 005). Whether the service *may* be exposed is decided here — no HTTP, a label
   * too long, a hostname another service holds, no base domain — because two of those are
   * cross-entity or platform-level, which an entity cannot see. The entity records the answer.
   */
  post("/{projectId}/{name}/expose") { (projectId: String, name: String) =>
    val current = entity(projectId, name)
      .call(ServiceEntity.desiredState)
      .invoke()
      .getOrElse(
        throw CommandError(s"no such service '$name' in project '$projectId'", ErrorCode.NotFound)
      )
    ExposureRules.refusal(current, deploy, hostnameHolder(current.key)).foreach { reason =>
      throw CommandError(reason, ErrorCode.Conflict)
    }
    withHostname(
      entity(projectId, name)
        .call(ServiceEntity.expose)
        .withMetadata(access(projectId, write = true))
        .invoke()
    )
  }

  post("/{projectId}/{name}/unexpose") { (projectId: String, name: String) =>
    withHostname(
      entity(projectId, name)
        .call(ServiceEntity.unexpose)
        .withMetadata(access(projectId, write = true))
        .invoke()
    )
  }

  /** The URL an exposed service answers at, added on the way out: the entity does not know it. */
  /**
   * What is behind each of a web-hosted service's mounts (feature 021), decided when one service is
   * read, from the mounted service's own entity: a listing's row cannot ask another entity, and a
   * row can lag. For people only; the proxy finds out for itself.
   */
  private def withMountStates(status: ServiceStatus): ServiceStatus =
    if status.mounts.isEmpty then status
    else
      status.copy(mounts = status.mounts.map { m =>
        val behind = entity(status.projectId, m.service).call(ServiceEntity.desiredState).invoke()
        m.copy(state = behind match
          case None                                            => "no service"
          case Some(s) if !s.descriptor.exists(_.service.http) => "serves no HTTP"
          case Some(s) if s.paused || s.suspended              => "paused"
          case Some(_)                                         => "ok")
      })

  private def withHostname(status: ServiceStatus): ServiceStatus =
    val located = status.copy(
      // Garage's is recorded as the bucket's path, and the address is the store's, which is
      // configuration. One in Google Cloud Storage is recorded whole, as the operator reported it.
      bucketAddress = status.bucketAddress.flatMap(address =>
        if address.startsWith("/") then deploy.bucketAddressFor(status.projectId, status.name)
        else Some(address)
      )
    )
    if status.exposed then
      located.copy(hostname = deploy.hostnameFor(status.projectId, status.name))
    else located

  /**
   * Another exposed service whose derived label equals this one's — `a-b` in `c` against `a` in
   * `b-c`. From the listing view, so it can lag a moment behind a just-exposed service; the gateway
   * then accepts one route and rejects the other, visibly, which is the backstop.
   */
  private def hostnameHolder(key: ServiceKey): Option[ServiceKey] =
    val label = Hostnames.label(key.name, key.projectId)
    services
      .ordered(jsonText("exposed") ++ sql" = 'true'", order = jsonText("name"))
      .map(row => ServiceKey(row.projectId, row.name))
      .find(other => other != key && Hostnames.label(other.name, other.projectId) == label)

  /**
   * A deployed service's output.
   *
   * On `ServiceEndpoint` rather than an endpoint of its own so it is governed by exactly the same
   * acl and the same project scoping as every other service command. Logs are not a side channel
   * around who may see what: if a caller cannot `get` a service, it cannot read what that service
   * printed either.
   *
   * Reading is all this does. The control plane holds `get` on pods and pods/log and no mutating
   * verb at all — see controlplane-rbac.yaml for why that keeps the split intact.
   */
  get("/{projectId}/{name}/logs") { (projectId: String, name: String) =>
    authz.project(principal, projectId, write = false)
    // Confirms the service exists, and 404s with the same message `services get` gives when it
    // does not — one vocabulary, rather than a second way of saying the same thing.
    val status = entity(projectId, name).call(ServiceEntity.get).invoke()

    // Read on the handler's own thread, which is where the request context lives.
    val instance = query.optional[String]("instance")
    val previous = query.flag("previous")
    val platform = query.flag("platform")
    val tail     = query.optional[Int]("tail")
    val since    = query.optional[Int]("since")

    // Which container: the developer's unless the platform's is asked for. A process- or
    // web-hosted pod has two, named as the operator names them — the platform's after the
    // service, the developer's with `-app`; every other pod has the one, and asking for the
    // platform's is refused rather than answered with the only one there is (feature 021).
    val twoContainers = ServiceEndpoint.TwoContainerHostings.contains(status.hosting)
    val container =
      if platform && !twoContainers then
        throw CommandError(ServiceEndpoint.PlatformRefusal, ErrorCode.BadRequest)
      else if twoContainers && !platform then s"$name-app"
      else name

    val instances = instance.map(Vector(_)).getOrElse(logs.instances(projectId, name))

    if instances.isEmpty then
      // Never an empty success: "this service logged nothing" and "this service is not running"
      // are different facts, and a reader who cannot tell them apart will chase the wrong one.
      throw CommandError(
        s"service '$name' has no running instance; it may be paused",
        ErrorCode.NotFound
      )
    else
      LogsResponse(
        instances.map { pod =>
          logs.read(projectId, pod, container, tail, since, previous) match
            case Right(output) => InstanceLogs(pod, output, error = None)
            case Left(problem) => InstanceLogs(pod, "", error = Some(problem))
        }
      )
  }

  /**
   * The service's topology, as its instances report it and merged (feature 019). Read as the
   * control plane, over a port only it may open; the member gets the merged document and no
   * credential for anything. Authorized exactly as logs: a non-member is told the service does not
   * exist.
   */
  get("/{projectId}/{name}/topology") { (projectId: String, name: String) =>
    authz.project(principal, projectId, write = false)
    val _    = entity(projectId, name).call(ServiceEntity.get).invoke()
    val read = topology.read(projectId, name)
    if read.isEmpty then
      throw CommandError(
        s"service '$name' has no running instance; it may be paused",
        ErrorCode.NotFound
      )
    else TopologyMerge.merge(name, read.size, read)
  }

  /** Who did what to this service, newest first (feature 008, FR-025). */
  get("/{projectId}/{name}/history") { (projectId: String, name: String) =>
    authz.project(principal, projectId, write = false)
    entity(projectId, name).call(ServiceEntity.history).invoke()
  }

  delete("/{projectId}/{name}") { (projectId: String, name: String) =>
    val authorized = authz.project(principal, projectId, write = true)
    val by         = authz.metadata(authorized)
    entity(projectId, name).call(ServiceEntity.delete).withMetadata(by).invoke(): Done
    usage.recordService(authorized.organizationId, ServiceKey(projectId, name).id, None, by)
    Done: Done
  }

  private def entity(projectId: String, name: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(ServiceKey(projectId, name).id))

object ServiceEndpoint:

  /**
   * One report per topic source over the instances: lags summed, the first failing reason kept
   * (feature 037).
   */
  def topicSourcesOf(documents: Vector[InstanceTopologyDocument]): Vector[TopicSourceReport] =
    documents
      .flatMap(_.topicSources)
      .groupBy(_.component)
      .toVector
      .sortBy(_._1)
      .map { (_, reports) =>
        val lags = reports.flatMap(_.lag)
        reports.head.copy(
          lag = Option.when(lags.nonEmpty)(lags.sum),
          failing = reports.flatMap(_.failing).headOption,
          behind = reports.exists(_.behind)
        )
      }

  /** The hostings whose pods hold the platform's container beside the developer's (R11). */
  val TwoContainerHostings: Set[String] = Set(ServiceSpec.Process, ServiceSpec.Web)

  /** The CLI's flag is named, because the CLI shows this refusal verbatim. */
  val PlatformRefusal: String = "--platform applies to a service with process or web hosting"
