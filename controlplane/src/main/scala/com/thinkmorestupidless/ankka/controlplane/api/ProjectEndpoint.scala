package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.controlplane.application.{
  ProjectEntity,
  ProjectRows,
  ServiceRows
}
import com.thinkmorestupidless.ankka.controlplane.auth.Authorization
import com.thinkmorestupidless.ankka.controlplane.deploy.{
  ProjectSchemaStore,
  ProjectSecretWriter,
  ProjectTopicsReader,
  RegistryWriter,
  TopologyReader
}
import com.thinkmorestupidless.ankka.controlplane.domain.{
  ConfigureRegistry,
  DeclareBroker,
  DeclareTopic,
  RemoveBroker,
  RemoveSecretEntry,
  RemoveTopic,
  SetSecretEntries
}
import com.thinkmorestupidless.ankka.controlplane.tenancy.OrganizationUsage
import com.thinkmorestupidless.ankka.core.{CommandError, Contract, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.core.graph.GraphJson
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray

import java.nio.charset.StandardCharsets.UTF_8
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlFragment
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

import scala.util.control.NonFatal

/**
 * Projects, optionally filtered by organization — the caller's organizations, that is: a project is
 * visible to the members of the organization that owns it and to nobody else (feature 008).
 */
final class ProjectEndpoint(
    clients: EndpointClients,
    val acl: Acl,
    protected val clock: java.time.Clock = java.time.Clock.systemUTC(),
    /**
     * Where a registry credential goes. `None` in a control plane with no cluster behind it, and
     * the registry routes then answer unavailable rather than recording a credential nothing holds.
     */
    registryWriter: Option[RegistryWriter] = None,
    /** Where a project secret's entries go; `None` as for the registry, with the same answer. */
    secretWriter: Option[ProjectSecretWriter] = None,
    /**
     * Where each declared topic's phase is read from (feature 027). `None`, or a cluster that
     * cannot be read, lists the topics with no phase: the declarations are the project's record,
     * and are answered whatever the cluster says.
     */
    topicsReader: Option[ProjectTopicsReader] = None,
    /** Where a contract's schema is held (feature 037); `None` refuses a declaration with one. */
    schemaStore: Option[ProjectSchemaStore] = None,
    /** Where each service's instances report what they state about a topic (feature 037). */
    topology: Option[TopologyReader] = None,
    /** The store the installation makes new buckets in (feature 039). */
    objectStore: com.thinkmorestupidless.ankka.controlplane.deploy.ObjectStoreKind =
      com.thinkmorestupidless.ankka.controlplane.deploy.ObjectStoreKind.Garage,
    /** The installation's backups (feature 041): what a project's status says without a target. */
    backups: => com.thinkmorestupidless.ankka.controlplane.BackupConfig =
      com.thinkmorestupidless.ankka.controlplane.BackupConfig.default,
    /**
     * Where a restore's services are asked what the broker holds past its moment (feature 041);
     * `None` asks their instances over the observe port.
     */
    divergence: Option[com.thinkmorestupidless.ankka.controlplane.deploy.DivergenceReader] = None,
    /**
     * Where the installation's copy to its secondary store is read (feature 041); the projector.
     */
    platform: Option[com.thinkmorestupidless.ankka.controlplane.deploy.PlatformBackupsReader] =
      None,
    /** Where a project's rehearsal namespace is made (feature 041); the projector. */
    rehearsalNamespaces: Option[
      com.thinkmorestupidless.ankka.controlplane.deploy.RehearsalNamespaces
    ] = None
) extends HttpEndpoint("/projects")
    with Attributing:

  private val projects = clients.viewClient.forView(ProjectRows)
  private val services = clients.viewClient.forView(ServiceRows)
  private val authz    = Authorization(clients, clock)
  private val usage    = OrganizationUsage(clients)

  /**
   * `GET /projects?organization=acme` — the filter is optional, and one outside the caller's
   * organizations yields an empty list rather than a refusal, for the reason a read of one does.
   */
  get("/") { () =>
    val mine = authz.visible(principal).map(_.id).toSet
    val condition = request.query.optional[String]("organization") match
      case Some(organizationId) => jsonText("organizationId") ++ sql" = $organizationId"
      case None                 => SqlFragment.empty
    projects
      .ordered(condition, order = jsonText("name"))
      .filter(detail => mine.contains(detail.organizationId))
      .map(detail => ProjectSummary.of(detail, serviceCount(detail.id)))
  }

  get("/{projectId}") { (projectId: String) =>
    authz.project(principal, projectId, write = false)
    val detail = entity(projectId).call(ProjectEntity.get).invoke()
    ProjectSummary.of(detail, serviceCount(detail.id))
  }

  /**
   * Creates a project, in an organization the caller is a member of.
   *
   * Checked here rather than in the entity: a project entity cannot see an organization, and a
   * command handler that called out to one would be making a check it could not hold — the
   * organization could be deleted a moment later either way. Doing it at the edge catches the typo,
   * which is what this check is actually for — and an organization the caller cannot see answers
   * exactly as one that does not exist.
   */
  postBody("/{projectId}") { (projectId: String, request: CreateProject) =>
    // The id becomes part of a Kubernetes namespace name, so one that cannot be expressed
    // there has to be refused when the project is created rather than when its first
    // service silently fails to deploy. Same rule the CLI applies before the round trip.
    val idProblems = ProjectId.problems(projectId)
    if idProblems.nonEmpty then throw CommandError(idProblems.mkString("; "), ErrorCode.BadRequest)
    val access = authz.requireMember(principal, request.organizationId, write = true)
    val by     = authz.metadata(access)
    // The organization is asked first (feature 015): a refusal for quota creates nothing, and a
    // create that then fails gives the slot back — only if this request was the one that took it.
    val reserved = usage.reserveProject(request.organizationId, projectId, by)
    try entity(projectId).call(ProjectEntity.createProject).withMetadata(by).invoke(request): Done
    catch
      case NonFatal(failure) =>
        if reserved then
          usage.undo(s"release project '$projectId'") {
            usage.releaseProject(request.organizationId, projectId, by)
          }
        throw failure
  }

  putBody("/{projectId}/name") { (projectId: String, request: Rename) =>
    val access = authz.project(principal, projectId, write = true)
    entity(projectId)
      .call(ProjectEntity.rename)
      .withMetadata(authz.metadata(access))
      .invoke(request.name)
  }

  delete("/{projectId}") { (projectId: String) =>
    val access    = authz.project(principal, projectId, write = true)
    val remaining = serviceCount(projectId)
    if remaining > 0 then
      throw CommandError(
        s"project '$projectId' still has $remaining service(s)",
        ErrorCode.Conflict
      )
    val by = authz.metadata(access)
    entity(projectId).call(ProjectEntity.delete).withMetadata(by).invoke(): Done
    usage.releaseProject(access.organizationId, projectId, by)
    Done: Done
  }

  /**
   * Registers a credential the cluster will pull this project's private images with.
   *
   * The cluster is written **first**, and a failure there is the end of it: the journal never
   * claims a credential the cluster does not hold, which is the one ordering that cannot leave a
   * service pointing at a Secret that does not exist. The password goes no further than the Secret
   * — not into the event, not into the reply, not into the listing.
   *
   * `write = true` membership, not ownership: a pipeline that pushes images to a registry is the
   * natural thing to register it, and a deploy token is a member.
   */
  putBody("/{projectId}/registry") { (projectId: String, request: SetRegistry) =>
    val access   = authz.project(principal, projectId, write = true)
    val problems = Registries.problems(request.server, request.username, request.password)
    if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)

    val writer = registryWriter.getOrElse(
      throw CommandError("this control plane cannot reach a cluster", ErrorCode.Unavailable)
    )
    try writer.writePullSecret(projectId, request.server, request.username, request.password)
    catch
      case error: CommandError => throw error
      case NonFatal(error) =>
        throw CommandError(
          s"could not write the registry credential: ${error.getMessage}",
          ErrorCode.Unavailable
        )

    entity(projectId)
      .call(ProjectEntity.configureRegistry)
      .withMetadata(authz.metadata(access))
      .invoke(ConfigureRegistry(request.server, request.username, Registries.SecretName)): Done
  }

  /**
   * Stops claiming a registry. The Secret stays where it is — the control plane holds no `delete`
   * on secrets, and one nothing names is inert. The next projection of each service in the project
   * drops the reference; `services restart` is what makes a running service notice.
   */
  delete("/{projectId}/registry") { (projectId: String) =>
    val access = authz.project(principal, projectId, write = true)
    entity(projectId)
      .call(ProjectEntity.clearRegistry)
      .withMetadata(authz.metadata(access))
      .invoke(): Done
  }

  // ── Project secrets ────────────────────────────────────────────────────────

  private def writing(what: String)(write: ProjectSecretWriter => Unit): Unit =
    val writer = secretWriter.getOrElse(
      throw CommandError("this control plane cannot reach a cluster", ErrorCode.Unavailable)
    )
    try write(writer)
    catch
      case error: CommandError => throw error
      case NonFatal(error) =>
        throw CommandError(s"could not $what: ${error.getMessage}", ErrorCode.Unavailable)

  /**
   * Sets entries of a project secret, merged into what it holds, in the project's namespace.
   *
   * In the registry's order: who may, what is wrong (all of it, at once), the cluster, and only
   * then the record — so the journal never names an entry the cluster does not hold. The values go
   * no further than the Secret: the record, the reply and the listing carry names alone.
   */
  putBody("/{projectId}/secrets/{name}") {
    (projectId: String, name: String, request: SetProjectSecret) =>
      val access   = authz.project(principal, projectId, write = true)
      val problems = ProjectSecrets.problems(name, request.entries)
      if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
      writing(s"write project secret '$name'")(_.setEntries(projectId, name, request.entries))
      entity(projectId)
        .call(ProjectEntity.setSecretEntries)
        .withMetadata(authz.metadata(access))
        .invoke(SetSecretEntries(name, request.entries.keys.toVector)): Done
  }

  /**
   * Removes one entry, named by `?entry=`. One the record does not have is not found, and the
   * cluster is not touched. The Secret itself is never deleted: the grant has no `delete`, and one
   * with no entry is inert and no longer listed.
   */
  delete("/{projectId}/secrets/{name}") { (projectId: String, name: String) =>
    val access = authz.project(principal, projectId, write = true)
    val entry  = query.required[String]("entry")
    val known  = entity(projectId).call(ProjectEntity.secrets).invoke()
    if !known.exists(s => s.name == name && s.entries.contains(entry)) then
      throw CommandError(s"project secret '$name' has no entry '$entry'", ErrorCode.NotFound)
    writing(s"remove entry '$entry' of project secret '$name'")(
      _.removeEntry(projectId, name, entry)
    )
    entity(projectId)
      .call(ProjectEntity.removeSecretEntry)
      .withMetadata(authz.metadata(access))
      .invoke(RemoveSecretEntry(name, entry)): Done
  }

  /**
   * Declares a topic on the project, or raises its partitions (feature 027). The record first, here
   * the reverse of a secret's order: a declaration is desired state, which `ProjectTopicsTrigger`
   * writes to the cluster after it, retrying until the cluster has it.
   */
  putBody("/{projectId}/topics/{name}") {
    (projectId: String, name: String, request: TopicDeclarationRequest) =>
      val access   = authz.project(principal, projectId, write = true)
      val problems = ProjectTopics.problems(name, request)
      if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
      // The schema first, into the project's store, then the record (feature 037): a failure after
      // the write leaves an unused document, never a declaration without its schema.
      val contract = request.contract.map { declared =>
        val document = writeToArray(declared.schema)
        val contract = Contract
          .fromSchema(declared.name, document)
          .fold(why => throw CommandError(s"topic '$name': $why", ErrorCode.BadRequest), identity)
        val store = schemaStore.getOrElse(
          throw CommandError(
            "the cluster is not configured; a contract cannot be held",
            ErrorCode.Unavailable
          )
        )
        store.putSchema(projectId, contract.fingerprint, new String(document, UTF_8))
        contract
      }
      entity(projectId)
        .call(ProjectEntity.declareTopic)
        .withMetadata(authz.metadata(access))
        .invoke(DeclareTopic(name, request.partitions, request.compacted, contract)): Done
  }

  /** The schema a topic's contract was declared with, as a member fetches it to build against. */
  get("/{projectId}/topics/{name}/schema") { (projectId: String, name: String) =>
    authz.project(principal, projectId, write = false): Unit
    val declared = entity(projectId).call(ProjectEntity.topics).invoke()
    val contract = declared
      .get(name)
      .flatMap(_.contract)
      .getOrElse(
        throw CommandError(
          s"project '$projectId' declares no contract on topic '$name'",
          ErrorCode.NotFound
        )
      )
    val document = schemaStore
      .flatMap(store => store.schema(projectId, contract.fingerprint))
      .getOrElse(
        throw CommandError(
          s"the schema of '${contract.name}' (${contract.fingerprint}) is not held for project '$projectId'",
          ErrorCode.NotFound
        )
      )
    val schema: GraphJson = GraphJson
      .parse(document.getBytes(UTF_8))
      .fold(
        why => throw CommandError(s"the held schema is not JSON: $why", ErrorCode.Internal),
        identity
      )
    schema
  }

  /** Stops declaring a topic. The topic and what was published to it stay on the broker. */
  delete("/{projectId}/topics/{name}") { (projectId: String, name: String) =>
    val access = authz.project(principal, projectId, write = true)
    entity(projectId)
      .call(ProjectEntity.removeTopic)
      .withMetadata(authz.metadata(access))
      .invoke(RemoveTopic(name)): Done
  }

  /**
   * The project's declared topics, from its own record, each with the phase the operator last
   * reported — absent when it has not, or the cluster cannot be read.
   */
  get("/{projectId}/topics") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    val declared = entity(projectId).call(ProjectEntity.topics).invoke()
    val reported =
      topicsReader
        .flatMap(reader =>
          try reader.topicStatus(projectId)
          catch case NonFatal(_) => None
        )
        .map(_.topics.map(t => t.name -> t).toMap)
        .getOrElse(Map.empty)
    // The sides every running service takes on the topics with a contract (feature 037): read
    // from each service's instances, and nothing when the cluster cannot be read.
    val checks: Map[String, Vector[TopicCheck]] =
      if declared.values.forall(_.contract.isEmpty) then Map.empty
      else
        topology.fold(Map.empty[String, Vector[TopicCheck]]) { reader =>
          try
            val names = services
              .ordered(jsonText("projectId") ++ sql" = $projectId", order = jsonText("name"))
              .map(_.name)
            TopicChecks.of(
              declared,
              names.flatMap(name => reader.read(projectId, name).flatMap(_._2).map(name -> _))
            )
          catch case NonFatal(_) => Map.empty
        }
    declared.toVector.sortBy(_._1).map { (name, topic) =>
      val status = reported.get(name)
      ProjectTopic(
        name,
        topic.partitions,
        status.map(s => ProjectEndpoint.topicPhrase(s.phase)),
        status.flatMap(_.detail),
        topic.compacted,
        topic.contract,
        checks.getOrElse(name, Vector.empty)
      )
    }
  }

  /**
   * The project's backups and database (feature 041): whether it is backed up, each line of
   * history's last base backup, how far back and how recently it can be restored, the archive's lag
   * and why it is failing; the project database's instances. Read live from what the operator last
   * reported; a cluster that cannot be read answers as one that has not reported.
   */
  get("/{projectId}/status") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    com.thinkmorestupidless.ankka.controlplane.BackupViews.project(
      projectId,
      reportedOf(projectId),
      backups,
      entity(projectId).call(ProjectEntity.restores).invoke(),
      entity(projectId).call(ProjectEntity.rehearsals).invoke(),
      copy = platform.flatMap(reader =>
        try com.thinkmorestupidless.ankka.controlplane.BackupViews.secondary(reader.copyStatus())
        catch case NonFatal(_) => None
      )
    )
  }

  /**
   * Rehearses a restore (feature 041): any member's to ask, since it changes nothing of the
   * project. The project's rehearsal namespace, and the one grant there that lets the operator
   * remove the rehearsal's database, are written to the cluster before the rehearsal is recorded,
   * so a rehearsal the project records is one the operator can finish. The moment is the latest the
   * project can be restored to unless one is given, and must be inside the line's window.
   */
  postBody("/{projectId}/rehearsals") {
    (projectId: String, request: com.thinkmorestupidless.ankka.controlplane.api.RehearsalRequest) =>
      val access = authz.project(principal, projectId, write = true)
      if !backups.enabled then
        throw CommandError(
          "the installation has no backup target, so there is nothing to rehearse a restore from",
          ErrorCode.BadRequest
        )
      val lines = reportedOf(projectId).flatMap(_.backups).toVector.flatMap(_.lines)
      val named = request.line.getOrElse(com.thinkmorestupidless.ankka.crd.Recovery.ProjectDatabase)
      val line = lines
        .find(_.line == named)
        .getOrElse(
          throw CommandError(
            s"$projectId has no backups of '$named' to rehearse from yet",
            ErrorCode.Conflict
          )
        )
      def instant(text: Option[String]) =
        text.flatMap(t => scala.util.Try(java.time.Instant.parse(t)).toOption)
      val earliest = instant(line.firstRestorable)
      val latest   = instant(line.lastRestorable)
      val moment = request.moment
        .orElse(latest)
        .getOrElse(
          throw CommandError(s"$projectId cannot be restored to any moment yet", ErrorCode.Conflict)
        )
      if earliest.forall(moment.isBefore) || latest.forall(moment.isAfter) then
        throw CommandError(
          s"$projectId can be restored between ${earliest.fold("no moment yet")(_.toString)} and " +
            s"${latest.fold("no moment yet")(_.toString)}",
          ErrorCode.BadRequest
        )
      val writer = rehearsalNamespaces.getOrElse(
        throw CommandError("the control plane has no cluster to rehearse in", ErrorCode.Unavailable)
      )
      try writer.ensureRehearsalNamespace(projectId)
      catch
        case NonFatal(failure) =>
          throw CommandError(
            s"the cluster refused the rehearsal namespace: ${failure.getMessage}",
            ErrorCode.Unavailable
          )
      val name = com.thinkmorestupidless.ankka.crd.Recovery.rehearsalName(clock.instant())
      entity(projectId)
        .call(ProjectEntity.requestRehearsal)
        .withMetadata(authz.metadata(access))
        .invoke(
          com.thinkmorestupidless.ankka.controlplane.domain
            .RequestRehearsal(name, line.line, moment)
        ): Unit
      com.thinkmorestupidless.ankka.controlplane.api.RehearsalView(
        name,
        line.line,
        moment,
        "Running",
        requestedAt = Some(clock.instant())
      )
  }

  /**
   * Issues the project's backup credential again (FR-003a): the operator mints a new key, writes it
   * where the archiver reads it, and deletes the old one, so a leaked key stops working. Owners
   * only.
   */
  post("/{projectId}/backups/credential") { (projectId: String) =>
    val access = authz.projectOwner(principal, projectId)
    if !backups.enabled then
      throw CommandError(
        "the installation has no backup target, so no backup credential",
        ErrorCode.BadRequest
      )
    val generation = entity(projectId)
      .call(ProjectEntity.reissueBackupCredential)
      .withMetadata(authz.metadata(access))
      .invoke(com.thinkmorestupidless.ankka.controlplane.domain.ReissueBackupCredential())
    com.thinkmorestupidless.ankka.controlplane.api.CredentialReissued(projectId, generation)
  }

  /** The project's rehearsals, oldest first: who asked, when, the moment, how each ended. */
  get("/{projectId}/rehearsals") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    com.thinkmorestupidless.ankka.controlplane.BackupViews.rehearsalViews(
      entity(projectId).call(ProjectEntity.rehearsals).invoke(),
      reportedOf(projectId)
    )
  }

  /**
   * Restores the project's database to a moment (feature 041): a new cluster beside the current
   * one, made from the line's latest base backup before the moment and its archive up to it. Owners
   * only. The moment must be inside the line's window as the operator last reported it, and with
   * the project's services on more than one cluster the request names the line.
   */
  postBody("/{projectId}/restores") { (projectId: String, request: RestoreRequest) =>
    val access = authz.projectOwner(principal, projectId)
    if !backups.enabled then
      throw CommandError(
        "the installation has no backup target, so there is nothing to restore from",
        ErrorCode.BadRequest
      )
    val lines = reportedOf(projectId).flatMap(_.backups).toVector.flatMap(_.lines)
    val line = request.line match
      case Some(named) =>
        lines
          .find(_.line == named)
          .getOrElse(
            throw CommandError(
              s"$projectId has no line of history '$named'; it has " +
                lines.map(_.line).mkString(", "),
              ErrorCode.BadRequest
            )
          )
      case None =>
        lines match
          case Vector(only) => only
          case Vector() =>
            throw CommandError(s"$projectId has no backups to restore from yet", ErrorCode.Conflict)
          case many =>
            throw CommandError(
              s"name the line: $projectId's services are on ${many.map(_.cluster).mkString(" and ")}",
              ErrorCode.BadRequest
            )
    def instant(text: Option[String]) =
      text.flatMap(t => scala.util.Try(java.time.Instant.parse(t)).toOption)
    val earliest = instant(line.firstRestorable)
    val latest   = instant(line.lastRestorable)
    if earliest.forall(request.moment.isBefore) || latest.forall(request.moment.isAfter) then
      throw CommandError(
        s"$projectId can be restored between ${earliest.fold("no moment yet")(_.toString)} and " +
          s"${latest.fold("no moment yet")(_.toString)}",
        ErrorCode.BadRequest
      )
    val name = com.thinkmorestupidless.ankka.crd.Recovery.restoreName(clock.instant())
    entity(projectId)
      .call(ProjectEntity.requestRestore)
      .withMetadata(authz.metadata(access))
      .invoke(
        com.thinkmorestupidless.ankka.controlplane.domain
          .RequestRestore(name, line.line, request.moment)
      ): Unit
    RestoreView(name, line.line, request.moment, "Restoring", requestedAt = Some(clock.instant()))
  }

  /** The project's restores, oldest first, each with how far it has got. */
  get("/{projectId}/restores") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    com.thinkmorestupidless.ankka.controlplane.BackupViews.restoreViews(
      entity(projectId).call(ProjectEntity.restores).invoke(),
      reportedOf(projectId)
    )
  }

  private lazy val divergenceReader = divergence.getOrElse(
    com.thinkmorestupidless.ankka.controlplane.deploy.DivergenceReader
      .observePort(
        com.thinkmorestupidless.ankka.controlplane.deploy.DeployConfig.default.namespacePrefix
      )
  )

  /**
   * One restore, with what each service's database holds once it is verified, and what the broker
   * holds past its moment, asked of those services as it is read: the broker is never restored, and
   * what it holds keeps growing after the restore, so a number kept from the moment of verifying
   * would be out of date when read.
   */
  get("/{projectId}/restores/{name}") { (projectId: String, name: String) =>
    authz.project(principal, projectId, write = false): Unit
    val view = com.thinkmorestupidless.ankka.controlplane.BackupViews
      .restoreViews(entity(projectId).call(ProjectEntity.restores).invoke(), reportedOf(projectId))
      .find(_.name == name)
      .getOrElse(throw CommandError(s"$projectId has no restore named $name", ErrorCode.NotFound))
    if view.phase != "Verified" && view.phase != "InUse" then view
    else
      val asked = view.services
        .filter(_.present)
        .map(v => v.name -> divergenceReader.since(projectId, v.name, view.moment))
      view.copy(
        broker = com.thinkmorestupidless.ankka.controlplane.deploy.DivergenceReader.merge(
          asked.collect { case (service, Right(entries)) => service -> entries }
        ),
        notAsked = asked.collect { case (service, Left(_)) => service },
        note = Some(com.thinkmorestupidless.ankka.controlplane.api.BackupPhrases.Republished)
      )
  }

  /** What the project asks of its database (feature 041); the defaults when it asked nothing. */
  get("/{projectId}/database") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    entity(projectId).call(ProjectEntity.database).invoke()
  }

  /**
   * Sets what the project asks of its database, whole: replicas, whether a write waits for one, how
   * long backups are kept (never below the installation's floor) and how often a restore is
   * rehearsed. A member's, as a service's descriptor is; the operator renders it.
   */
  putBody("/{projectId}/database") {
    (projectId: String, setting: com.thinkmorestupidless.ankka.controlplane.api.DatabaseSetting) =>
      val access   = authz.project(principal, projectId, write = true)
      val problems = setting.problems(backups.retentionDays)
      if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
      // A schedule needs somewhere to rehearse, and only the control plane makes namespaces: the
      // cluster first, then the record, as for a rehearsal asked for by hand.
      if setting.rehearse.isDefined then
        val writer = rehearsalNamespaces.getOrElse(
          throw CommandError(
            "the control plane has no cluster to rehearse in",
            ErrorCode.Unavailable
          )
        )
        try writer.ensureRehearsalNamespace(projectId)
        catch
          case NonFatal(failure) =>
            throw CommandError(
              s"the cluster refused the rehearsal namespace: ${failure.getMessage}",
              ErrorCode.Unavailable
            )
      entity(projectId)
        .call(ProjectEntity.setDatabase)
        .withMetadata(authz.metadata(access))
        .invoke(com.thinkmorestupidless.ankka.controlplane.domain.SetDatabase(setting)): Unit
      setting
  }

  /** Who did what to the project's database, newest first. */
  get("/{projectId}/history") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    entity(projectId)
      .call(ProjectEntity.history)
      .invoke()
      .reverse
      .map(e =>
        ProjectHistoryView(
          e.kind,
          e.actor.flatMap(a => a.display.orElse(Some(a.subject))),
          e.at,
          e.detail
        )
      )
  }

  /** What the operator last reported of the project; nothing when the cluster cannot be read. */
  private def reportedOf(projectId: String) =
    topicsReader.flatMap(reader =>
      try reader.topicStatus(projectId)
      catch case NonFatal(_) => None
    )

  /**
   * Declares a broker on the project (feature 037), or changes where it is. The record alone: the
   * operator mounts its secret.
   */
  putBody("/{projectId}/brokers/{name}") {
    (projectId: String, name: String, request: BrokerDeclarationRequest) =>
      val access   = authz.project(principal, projectId, write = true)
      val problems = ProjectBrokers.problems(name, request)
      if problems.nonEmpty then throw CommandError(problems.mkString("; "), ErrorCode.BadRequest)
      entity(projectId)
        .call(ProjectEntity.declareBroker)
        .withMetadata(authz.metadata(access))
        .invoke(DeclareBroker(name, request.bootstrap.trim, request.shape, request.secret)): Done
  }

  /** Stops declaring a broker. A service naming it is refused at its next start. */
  delete("/{projectId}/brokers/{name}") { (projectId: String, name: String) =>
    val access = authz.project(principal, projectId, write = true)
    entity(projectId)
      .call(ProjectEntity.removeBroker)
      .withMetadata(authz.metadata(access))
      .invoke(RemoveBroker(name)): Done
  }

  /**
   * Names where the project's new buckets in Google Cloud Storage are made (feature 039), in the
   * installation's own words; the cloud provider says what they mean. Refused where the
   * installation keeps new buckets in Garage, which has no location to choose.
   */
  putBody("/{projectId}/location") { (projectId: String, request: SetProjectLocation) =>
    val access = authz.project(principal, projectId, write = true)
    locatable()
    if request.location.trim.isEmpty then
      throw CommandError("a location must name something", ErrorCode.BadRequest)
    entity(projectId)
      .call(ProjectEntity.setLocation)
      .withMetadata(authz.metadata(access))
      .invoke(request): Done
  }

  /** Lets the installation's default location apply to the project's new buckets again. */
  delete("/{projectId}/location") { (projectId: String) =>
    val access = authz.project(principal, projectId, write = true)
    locatable()
    entity(projectId)
      .call(ProjectEntity.setLocation)
      .withMetadata(authz.metadata(access))
      .invoke(SetProjectLocation("")): Done
  }

  private def locatable(): Unit =
    if objectStore != com.thinkmorestupidless.ankka.controlplane.deploy.ObjectStoreKind.Gcs then
      throw CommandError(
        "the installation keeps new buckets in Garage, which has no location to choose",
        ErrorCode.Conflict
      )

  /** The project's declared brokers. */
  get("/{projectId}/brokers") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    entity(projectId)
      .call(ProjectEntity.brokers)
      .invoke()
      .toVector
      .sortBy(_._1)
      .map { (name, b) =>
        ProjectBroker(name, b.bootstrap, b.shape, b.secretName, b.declaredAt.map(_.toString))
      }
  }

  /**
   * The project's secrets, by name, from the project's own record: names and entries, never a
   * value.
   */
  get("/{projectId}/secrets") { (projectId: String) =>
    authz.project(principal, projectId, write = false): Unit
    entity(projectId).call(ProjectEntity.secrets).invoke()
  }

  private def serviceCount(projectId: String): Int =
    services.count(jsonText("projectId") ++ sql" = $projectId").toInt

  private def entity(projectId: String) =
    clients.componentClient.forEventSourcedEntity(EntityId(projectId))

object ProjectEndpoint:

  /** The operator's reported phase of a topic, as the phrase `ProjectTopic.phase` documents. */
  def topicPhrase(phase: String): String = phase match
    case "Waiting"     => "waiting for broker"
    case "Provisioned" => "provisioned"
    case "Recovered"   => "recovered"
    case "Failed"      => "failed"
    case other         => other
