package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{
  BrokerDeclarationRequest,
  CreateProject,
  GrantRules,
  GrantState,
  GrantTarget,
  Grantee,
  ProjectBrokers,
  ProjectDetail,
  ProjectSecretSummary,
  ProjectTopics,
  RegistrySummary,
  SetProjectLocation,
  Setting,
  SettingChange,
  TopicSettingsRules
}
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.ProjectEvent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A project: the scope a service name is unique within.
 *
 * The parent organization is validated by the endpoint rather than here. An entity can only see its
 * own state, and a command handler that called out to another entity to check a precondition would
 * be making that check non-atomic anyway — so the check belongs where the request arrives, once.
 */
final class ProjectEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Project, ProjectEvent]:

  def emptyState: Project = Project(context.entityId, "", "")

  def applyEvent(event: ProjectEvent): Project = event match
    case ProjectCreated(name, organizationId, _, _) => currentState.onCreated(name, organizationId)
    case ProjectRenamed(name, _, _)                 => currentState.onRenamed(name)
    case ProjectDeleted(actor, at)                  => currentState.onDeleted(actor, at)
    case RegistryConfigured(server, username, secretName, actor, at) =>
      currentState.onRegistryConfigured(server, username, secretName, actor, at)
    case _: RegistryCleared => currentState.onRegistryCleared
    case ProjectSecretEntriesSet(name, entries, actor, at) =>
      currentState.onSecretEntriesSet(name, entries, actor, at)
    case ProjectSecretEntryRemoved(name, entry, _, _) =>
      currentState.onSecretEntryRemoved(name, entry)
    case ProjectTopicDeclared(
          name,
          partitions,
          actor,
          at,
          compacted,
          contract,
          settings,
          defaulted,
          changes
        ) =>
      currentState.onTopicDeclared(
        name,
        partitions,
        at,
        compacted,
        contract,
        settings,
        defaulted,
        actor,
        changes
      )
    case ProjectTopicSettingsFilled(name, settings, defaulted, at) =>
      currentState.onTopicSettingsFilled(name, settings, defaulted, at)
    case ProjectBrokerDeclared(name, bootstrap, shape, secretName, _, at) =>
      currentState.onBrokerDeclared(name, DeclaredBroker(bootstrap, shape, secretName, at))
    case ProjectBrokerRemoved(name, _, _)     => currentState.onBrokerRemoved(name)
    case ProjectLocationSet(location, _, _)   => currentState.onLocationSet(location)
    case ProjectTopicRemoved(name, actor, at) => currentState.onTopicRemoved(name, actor, at)
    case ProjectRestoreRequested(name, line, targetTime, actor, at) =>
      currentState.onRestoreRequested(name, line, targetTime, actor, at)
    case ProjectRestoreEnded(name, succeeded, reachedAt, detail, at) =>
      currentState.onRestoreEnded(name, succeeded, reachedAt, detail, at)
    case ProjectDatabaseSet(setting, actor, at) => currentState.onDatabaseSet(setting, actor, at)
    case ProjectBackupCredentialReissued(generation, actor, at) =>
      currentState.onBackupCredentialReissued(generation, actor, at)
    case ProjectRehearsalRequested(name, line, targetTime, actor, at) =>
      currentState.onRehearsalRequested(name, line, targetTime, actor, at)
    case ProjectRehearsalEnded(name, line, targetTime, outcome, elapsed, detail, at) =>
      currentState.onRehearsalEnded(name, line, targetTime, outcome, elapsed, detail, at)
    case GrantMade(id, grantee, target, pending, actor, at) =>
      currentState.onGrantMade(id, grantee, target, pending, actor, at)
    case GrantAccepted(id, actor, at) =>
      currentState.onGrantAnswered(id, GrantState.Accepted, actor, at)
    case GrantDeclined(id, actor, at) =>
      currentState.onGrantAnswered(id, GrantState.Declined, actor, at)
    case GrantWithdrawn(id, actor, at) =>
      currentState.onGrantEnded(id, GrantState.Withdrawn, actor, at)
    case GrantRevoked(id, actor, at) => currentState.onGrantEnded(id, GrantState.Revoked, actor, at)
    case GrantRelinquished(id, actor, at) =>
      currentState.onGrantEnded(id, GrantState.Relinquished, actor, at)
    case GrantLapsed(id, actor, at) => currentState.onGrantEnded(id, GrantState.Lapsed, actor, at)
    case GrantRecorded(id, project, organization, grantee, target, change, actor, at) =>
      currentState.onGrantRecorded(
        GrantRecordedFields(id, project, organization, grantee, target, change, actor, at)
      )

  /** A new backup credential (FR-003a): the next generation, each ask its own. */
  def reissueBackupCredential(request: ReissueBackupCredential): Effect[Int] =
    val _ = request
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      val next = currentState.backupCredentialGeneration + 1
      effects.persist(ProjectBackupCredentialReissued(next, actor, at)).thenReply(_ => next)

  def backupCredentialGeneration: ReadOnlyEffect[Int] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.backupCredentialGeneration)

  /** A rehearsal (feature 041): refused while another runs, and a name is never used twice. */
  def requestRehearsal(request: RequestRehearsal): Effect[Done] =
    if !currentState.exists then notFound
    else
      currentState.rehearsalInProgress match
        case Some(running) =>
          effects.error(
            s"a rehearsal of ${context.entityId} is in progress: $running",
            ErrorCode.Conflict
          )
        case None if currentState.rehearsals.contains(request.name) =>
          effects.error(s"a rehearsal named ${request.name} already exists", ErrorCode.Conflict)
        case None =>
          effects
            .persist(
              ProjectRehearsalRequested(request.name, request.line, request.targetTime, actor, at)
            )
            .thenReply(_ => Done)

  /** What the operator reported of a rehearsal: its end, once; anything else records nothing. */
  def observeRehearsal(observed: ObserveRehearsal): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.rehearsals.get(observed.name).exists(_.outcome.isDefined) then
      effects.reply(Done)
    else if !Set("Completed", "Failed", "NotRemoved").contains(observed.outcome) then
      effects.reply(Done)
    else
      effects
        .persist(
          ProjectRehearsalEnded(
            observed.name,
            observed.line,
            observed.targetTime,
            observed.outcome,
            observed.elapsedSeconds,
            observed.detail,
            at
          )
        )
        .thenReply(_ => Done)

  def rehearsals: ReadOnlyEffect[Map[String, Rehearsal]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.rehearsals)

  /**
   * What the project asks of its database (feature 041); the same setting again records nothing.
   */
  def setDatabase(request: SetDatabase): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.database.contains(request.setting) then effects.reply(Done)
    else effects.persist(ProjectDatabaseSet(request.setting, actor, at)).thenReply(_ => Done)

  def database: ReadOnlyEffect[com.thinkmorestupidless.ankka.controlplane.api.DatabaseSetting] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(
        currentState.database.getOrElse(
          com.thinkmorestupidless.ankka.controlplane.api.DatabaseSetting()
        )
      )

  /**
   * A restore of the project's database (feature 041). Refused while another has not ended, and a
   * name already used is refused: a restore's cluster is never made twice. The moment's window and
   * the line are the endpoint's to check, from what the cluster reports.
   */
  def requestRestore(request: RequestRestore): Effect[Done] =
    if !currentState.exists then notFound
    else
      currentState.restoreInProgress match
        case Some(running) =>
          effects.error(
            s"a restore of ${context.entityId} is in progress: $running",
            ErrorCode.Conflict
          )
        case None if currentState.restores.contains(request.name) =>
          effects.error(s"a restore named ${request.name} already exists", ErrorCode.Conflict)
        case None =>
          effects
            .persist(
              ProjectRestoreRequested(request.name, request.line, request.targetTime, actor, at)
            )
            .thenReply(_ => Done)

  /** What the operator reported of a restore; it ends once, and a repeat records nothing. */
  def observeRestore(observed: ObserveRestore): Effect[Done] =
    currentState.restores.get(observed.name) match
      case None => effects.error(s"no restore named ${observed.name}", ErrorCode.NotFound)
      case Some(restore) if restore.outcome.isDefined => effects.reply(Done)
      case Some(_) =>
        observed.phase match
          case "Verified" | "InUse" =>
            effects
              .persist(ProjectRestoreEnded(observed.name, true, observed.reachedAt, None, at))
              .thenReply(_ => Done)
          case "Failed" =>
            effects
              .persist(
                ProjectRestoreEnded(observed.name, false, None, observed.detail, at)
              )
              .thenReply(_ => Done)
          case _ => effects.reply(Done)

  def restores: ReadOnlyEffect[Map[String, Restore]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.restores)

  def history: ReadOnlyEffect[Vector[ProjectHistoryEntry]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.history)

  def create(request: CreateProject): Effect[Done] =
    if currentState.deleted then
      effects.error(
        s"project '${context.entityId}' was deleted; its id is not reused",
        ErrorCode.Conflict
      )
    else if currentState.known then
      effects.error(s"project '${context.entityId}' already exists", ErrorCode.Conflict)
    else if request.name.isEmpty then effects.error("project name must not be empty")
    else if request.organizationId.isEmpty then effects.error("project needs an organization")
    else
      effects
        .persist(ProjectCreated(request.name, request.organizationId, actor, at))
        .thenReply(_ => Done)

  def rename(name: String): Effect[Done] =
    if !currentState.exists then notFound
    else if name.isEmpty then effects.error("project name must not be empty")
    else effects.persist(ProjectRenamed(name, actor, at)).thenReply(_ => Done)

  def delete: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(ProjectDeleted(actor, at)).thenReply(_ => Done)

  /**
   * Record a registry credential the cluster already holds.
   *
   * The Secret is written before this is called, and a failure there stops the sequence — so this
   * never records a credential the cluster does not have. It is idempotent by construction: a
   * second call for the same server simply replaces the reference.
   */
  def configureRegistry(request: ConfigureRegistry): Effect[Done] =
    if !currentState.exists then notFound
    else if request.server.isEmpty then effects.error("registry server must not be empty")
    else if request.username.isEmpty then effects.error("registry username must not be empty")
    else if request.secretName.isEmpty then effects.error("a registry needs a secret to name")
    else
      effects
        .persist(
          RegistryConfigured(request.server, request.username, request.secretName, actor, at)
        )
        .thenReply(_ => Done)

  /**
   * Stop claiming a registry. The Secret stays in the cluster: the control plane holds no `delete`
   * on secrets, and one nothing references is inert.
   */
  def clearRegistry: Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.registry.isEmpty then
      effects.error(s"project '${context.entityId}' has no registry", ErrorCode.NotFound)
    else effects.persist(RegistryCleared(actor, at)).thenReply(_ => Done)

  /**
   * Record entries of a project secret the cluster already holds. Called only after the cluster
   * took them, so this never records an entry the cluster does not have. Names only: no value
   * reaches here.
   */
  def setSecretEntries(request: SetSecretEntries): Effect[Done] =
    if !currentState.exists then notFound
    else if request.name.isEmpty then effects.error("a project secret needs a name")
    else if request.entries.isEmpty then
      effects.error("a project secret is set with at least one entry")
    else
      effects
        .persist(ProjectSecretEntriesSet(request.name, request.entries.distinct.sorted, actor, at))
        .thenReply(_ => Done)

  /**
   * Record that one entry was removed. One the record does not have is not found, and nothing
   * changes.
   */
  def removeSecretEntry(request: RemoveSecretEntry): Effect[Done] =
    if !currentState.exists then notFound
    else if !currentState.secrets.get(request.name).exists(_.entries.contains(request.entry)) then
      effects.error(
        s"project secret '${request.name}' has no entry '${request.entry}'",
        ErrorCode.NotFound
      )
    else if currentState.brokers.exists((_, d) =>
        d.secretName == request.name && ProjectBrokers.needs(d.shape).contains(request.entry)
      )
    then
      // Feature 037: a broker's credential is not pulled from under it; the broker goes first.
      val named = currentState.brokers.collect {
        case (b, d) if d.secretName == request.name => b
      }
      effects.error(
        s"project secret '${request.name}' is the credential of broker " +
          s"${named.toVector.sorted.map(b => s"'$b'").mkString(", ")}; remove the broker first",
        ErrorCode.Conflict
      )
    else
      effects
        .persist(ProjectSecretEntryRemoved(request.name, request.entry, actor, at))
        .thenReply(_ => Done)

  /**
   * Declare a topic on the project, or raise its partitions (feature 027), or change its settings
   * (feature 043). Its rules are the project's own state, so every one is checked here: a name and
   * a count the broker can hold, never fewer partitions than the project declares, and copies fixed
   * once declared. A declaration that changes nothing records nothing. The settings arrive filled
   * or merged by the endpoint, which knows the installation's defaults and has checked its bounds.
   */
  def declareTopic(request: DeclareTopic): Effect[Done] =
    if !currentState.exists then notFound
    else
      val has = currentState.topics.get(request.name)
      request.partitions.orElse(has.map(_.partitions)) match
        case None =>
          effects.error(s"topic '${request.name}': ${TopicSettingsRules.PartitionsRequired}")
        case Some(partitions) =>
          val problems = ProjectTopics.problems(request.name, partitions) ++
            request.settings.toVector.flatMap(TopicSettingsRules.coherent(request.name, _))
          if problems.nonEmpty then effects.error(problems.mkString("; "))
          else
            val before = has.flatMap(_.settings)
            val after  = request.settings.orElse(before)
            val fixed = (has, request.settings) match
              case (Some(_), Some(now)) =>
                now.copies != before.flatMap(_.copies) || now.minInSync != before.flatMap(
                  _.minInSync
                )
              case _ => false
            val changes: Vector[SettingChange] = (before, request.settings) match
              case (Some(b), Some(a)) => TopicSettingsRules.changes(b, a)
              case (None, Some(a)) if has.isDefined =>
                Setting.values.toVector.collect {
                  case setting if setting != Setting.Copies && setting != Setting.MinInSync =>
                    SettingChange(setting, "unstated", a.valueOf(setting))
                }
              case _ => Vector.empty
            val compacted = after.fold(request.compacted)(_.compacted)
            has match
              case Some(had) if had.partitions > partitions =>
                effects.error(
                  ProjectTopics.fewer(request.name, had.partitions, partitions),
                  ErrorCode.Conflict
                )
              case Some(_) if fixed =>
                effects.error(
                  s"topic '${request.name}': ${TopicSettingsRules.FixedCopies}",
                  ErrorCode.Conflict
                )
              case Some(had)
                  if had.partitions == partitions && had.compacted == compacted &&
                    had.contract == request.contract && changes.isEmpty &&
                    (request.settings.isEmpty || had.defaulted == request.defaulted.toSet) =>
                effects.reply(Done)
              case _ =>
                effects
                  .persist(
                    ProjectTopicDeclared(
                      request.name,
                      partitions,
                      actor,
                      at,
                      compacted,
                      request.contract,
                      request.settings,
                      request.defaulted,
                      changes
                    )
                  )
                  .thenReply(_ => Done)

  /**
   * Fill every topic declared before topics stated their settings (feature 043): the installation's
   * defaults then in force, compacted if the topic was, its copies left as the broker's. A project
   * with nothing to fill records nothing, so the control plane's sweep may ask on every start.
   */
  def fillTopicSettings(request: FillTopicSettings): Effect[FilledTopics] =
    if !currentState.exists then effects.reply(FilledTopics(Vector.empty))
    else
      val unfilled = currentState.topics.toVector.sortBy(_._1).collect {
        case (name, t) if t.settings.isEmpty => name -> t
      }
      if unfilled.isEmpty then effects.reply(FilledTopics(Vector.empty))
      else
        val now = at.orElse(Some(java.time.Instant.now()))
        val events = unfilled.map { (name, t) =>
          val filled = TopicSettingsRules.legacy(t.compacted, request.defaults)
          ProjectTopicSettingsFilled(
            name,
            filled.settings,
            Setting.values.toVector.filter(filled.defaulted),
            now
          )
        }
        effects.persistAll(events).thenReply(_ => FilledTopics(unfilled.map(_._1)))

  /** Stop declaring a topic. Nothing on the broker is removed; one not declared is not found. */
  def removeTopic(request: RemoveTopic): Effect[Done] =
    if !currentState.exists then notFound
    else if !currentState.topics.contains(request.name) then
      effects.error(
        s"project '${context.entityId}' declares no topic '${request.name}'",
        ErrorCode.NotFound
      )
    else effects.persist(ProjectTopicRemoved(request.name, actor, at)).thenReply(_ => Done)

  /**
   * Declare a broker on the project, or change where it is (feature 037). The secret must be one
   * the project has set, with the entries the shape needs, which this entity's own record says
   * exactly.
   */
  def declareBroker(request: DeclareBroker): Effect[Done] =
    if !currentState.exists then notFound
    else
      val problems = ProjectBrokers.problems(
        request.name,
        BrokerDeclarationRequest(request.bootstrap, request.shape, request.secretName)
      )
      if problems.nonEmpty then effects.error(problems.mkString("; "))
      else
        currentState.secrets.get(request.secretName) match
          case None =>
            effects.error(
              s"project '${context.entityId}' has no project secret '${request.secretName}'",
              ErrorCode.BadRequest
            )
          case Some(secret) =>
            val missing = ProjectBrokers.needs(request.shape).filterNot(secret.entries)
            if missing.nonEmpty then
              effects.error(ProjectBrokers.lacking(request.secretName, request.shape, missing))
            else
              currentState.brokers.get(request.name) match
                case Some(has)
                    if has.bootstrap == request.bootstrap && has.shape == request.shape &&
                      has.secretName == request.secretName =>
                  effects.reply(Done)
                case _ =>
                  effects
                    .persist(
                      ProjectBrokerDeclared(
                        request.name,
                        request.bootstrap,
                        request.shape,
                        request.secretName,
                        actor,
                        at
                      )
                    )
                    .thenReply(_ => Done)

  /** Stop declaring a broker. A service naming it is refused at its next start. */
  def removeBroker(request: RemoveBroker): Effect[Done] =
    if !currentState.exists then notFound
    else if !currentState.brokers.contains(request.name) then
      effects.error(
        s"project '${context.entityId}' declares no broker '${request.name}'",
        ErrorCode.NotFound
      )
    else effects.persist(ProjectBrokerRemoved(request.name, actor, at)).thenReply(_ => Done)

  /**
   * Names where the project's new buckets in Google Cloud Storage are made, or, with none, lets the
   * installation's default apply (feature 039). The same location again records nothing.
   */
  def setLocation(request: SetProjectLocation): Effect[Done] =
    val location = Option(request.location).map(_.trim).filter(_.nonEmpty)
    if !currentState.exists then notFound
    else if currentState.bucketLocation == location then effects.reply(Done)
    else effects.persist(ProjectLocationSet(location, actor, at)).thenReply(_ => Done)

  /** Where the project's new buckets are made, when it names one. */
  def bucketLocation: ReadOnlyEffect[Option[String]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.bucketLocation)

  /**
   * Make a grant (feature 040). The project's own rules are checked here, where its state is: the
   * target's shape, a topic the project declares, no grant to one of its own services. Whether the
   * grantee is of another organization — so whether the grant waits — the endpoint has decided, as
   * it has that the grantee's project exists. The same live grant again is the same grant: its
   * record is the reply and nothing is persisted.
   */
  def makeGrant(request: MakeGrant): Effect[Grant] =
    if !currentState.exists then notFound
    else
      val problems = Grantee.problems(request.grantee) ++ GrantTarget.problems(request.target)
      val undeclared = request.target.topic.filter(t =>
        request.target.kind == GrantTarget.Topic && !currentState.topics.contains(t)
      )
      if problems.nonEmpty then effects.error(problems.mkString("; "))
      else if undeclared.nonEmpty then
        effects.error(GrantRules.undeclared(context.entityId, undeclared.get), ErrorCode.NotFound)
      else
        request.grantee match
          case Grantee.Service(project, _) if project == context.entityId =>
            effects.error(GrantRules.ownService)
          case _ =>
            currentState.liveGrant(request.grantee, request.target) match
              case Some(live) => effects.reply(live)
              case None if currentState.grants.contains(request.id) =>
                effects.error(s"grant '${request.id}' already exists", ErrorCode.Conflict)
              case None =>
                effects
                  .persist(
                    GrantMade(
                      request.id,
                      request.grantee,
                      request.target,
                      request.pending,
                      actor,
                      at
                    )
                  )
                  .thenReply(_.grants(request.id))

  /** The grantor takes back a grant nobody has answered yet. */
  def withdrawGrant(id: String): Effect[Done] =
    change(id, "withdraw", GrantState.Pending, "a pending")(GrantWithdrawn(id, actor, at))

  /** The grantor ends an accepted grant, without the grantee. */
  def revokeGrant(id: String): Effect[Done] =
    change(id, "revoke", GrantState.Accepted, "an accepted")(GrantRevoked(id, actor, at))

  /** An owner of the grantee's organization takes a pending grant; the endpoint checked who. */
  def acceptGrant(id: String): Effect[Done] =
    change(id, "accept", GrantState.Pending, "a pending")(GrantAccepted(id, actor, at))

  /** An owner of the grantee's organization refuses a pending grant. */
  def declineGrant(id: String): Effect[Done] =
    change(id, "decline", GrantState.Pending, "a pending")(GrantDeclined(id, actor, at))

  /** An owner of the grantee's organization gives up an accepted grant, without the grantor. */
  def relinquishGrant(id: String): Effect[Done] =
    change(id, "relinquish", GrantState.Accepted, "an accepted")(GrantRelinquished(id, actor, at))

  /**
   * The grantee was deleted. Ends a live grant with the deleter's attribution; on a grant already
   * ended it records nothing and succeeds, since the consumer that sends it may send it twice.
   */
  def lapseGrant(id: String): Effect[Done] =
    if !currentState.exists then notFound
    else
      currentState.grants.get(id) match
        case None                     => noSuchGrant(id)
        case Some(g) if !g.state.live => effects.reply(Done)
        case Some(_) => effects.persist(GrantLapsed(id, actor, at)).thenReply(_ => Done)

  /**
   * Record a change to a grant another project made to one of this project's services. Written only
   * by the consumer that follows the granting project; a change already recorded is a conflict,
   * which that consumer reads as done.
   */
  def recordGrantChange(request: RecordGrantChange): Effect[Done] =
    if !currentState.exists then notFound
    else if currentState.received.get(request.id).exists(_.recorded(request.change)) then
      effects.error(
        s"grant '${request.id}' already records ${request.change.word}",
        ErrorCode.Conflict
      )
    else
      effects
        .persist(
          GrantRecorded(
            request.id,
            request.grantingProject,
            request.grantingOrganization,
            request.grantee,
            request.target,
            request.change,
            actor,
            at
          )
        )
        .thenReply(_ => Done)

  /** Every grant the project has made, live and ended, oldest first. */
  def grants: ReadOnlyEffect[Vector[Grant]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.grants.values.toVector.sortBy(g => (g.granted.at, g.id)))

  /**
   * What other projects granted this project's services. Readable on a deleted project too: a
   * deletion lapses the grants its services held, and the consumer that does it reads them here.
   */
  def receivedGrants: ReadOnlyEffect[Vector[ReceivedGrant]] =
    if !currentState.known then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.received.values.toVector.sortBy(_.id))

  /** Who deleted the project and when; nothing while it exists or was never created. */
  def deletion: ReadOnlyEffect[Option[Deletion]] =
    effects.reply(Option.when(currentState.deleted)(currentState.deletion).flatten)

  private def change(id: String, verb: String, from: GrantState, applies: String)(
      event: ProjectEvent
  ): Effect[Done] =
    if !currentState.exists then notFound
    else
      currentState.grants.get(id) match
        case None => noSuchGrant(id)
        case Some(g) if g.state != from =>
          effects.error(GrantRules.cannot(id, g.state, verb, applies), ErrorCode.Conflict)
        case Some(_) => effects.persist(event).thenReply(_ => Done)

  private def noSuchGrant(id: String) =
    effects.error(s"project '${context.entityId}' has no grant '$id'", ErrorCode.NotFound)

  /** The project's declared brokers, by name. */
  def brokers: ReadOnlyEffect[Map[String, DeclaredBroker]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.brokers)

  /** The project's declared topics, by name. */
  def topics: ReadOnlyEffect[Map[String, DeclaredTopic]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.topics)

  /** The project's secrets by name, from this entity's own record: exact, and never a value. */
  def secrets: ReadOnlyEffect[Vector[ProjectSecretSummary]] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(
        currentState.secrets.toVector.sortBy(_._1).map { (name, ref) =>
          ProjectSecretSummary(
            name,
            ref.entries.toVector.sorted,
            ref.setAt,
            ref.setBy.flatMap(_.display)
          )
        }
      )

  def get: ReadOnlyEffect[ProjectDetail] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else
      effects.reply(
        ProjectDetail(
          currentState.id,
          currentState.name,
          currentState.organizationId,
          currentState.registry.map(r =>
            RegistrySummary(r.server, r.username, r.setAt, r.setBy.flatMap(_.display))
          )
        )
      )

  /** What a projection needs: the reference itself, secret name included, or nothing. */
  def registry: ReadOnlyEffect[Option[RegistryRef]] = effects.reply(currentState.registry)

  def exists: ReadOnlyEffect[Boolean] = effects.reply(currentState.exists)

  private def attribution: Option[Attribution] = Attribution.from(commandContext.metadata)
  private def actor: Option[Actor]             = attribution.map(_.actor)
  private def at: Option[java.time.Instant]    = attribution.map(_.at)

  private def notFoundMessage = s"no such project '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)

object ProjectEntity
    extends EventSourcedEntity.Companion[ProjectEntity, Project, ProjectEvent](
      componentId = ComponentId("project"),
      stateSerializer = Codecs.serializer[Project]("project"),
      eventSerializer = Codecs.serializer[ProjectEvent]("project-event")
    ):

  given Serializer[CreateProject]       = Codecs.serializer[CreateProject]("create-project")
  given Serializer[ProjectDetail]       = Codecs.serializer[ProjectDetail]("project-detail")
  given Serializer[ConfigureRegistry]   = Codecs.serializer[ConfigureRegistry]("configure-registry")
  given Serializer[Option[RegistryRef]] = Codecs.serializer[Option[RegistryRef]]("registry-ref")
  given Serializer[SetSecretEntries]    = Codecs.serializer[SetSecretEntries]("set-secret-entries")
  given Serializer[RemoveSecretEntry] = Codecs.serializer[RemoveSecretEntry]("remove-secret-entry")
  given Serializer[Vector[ProjectSecretSummary]] =
    Codecs.serializer[Vector[ProjectSecretSummary]]("project-secrets")
  given Serializer[DeclareTopic] = Codecs.serializer[DeclareTopic]("declare-topic")
  given deletionSerializer: Serializer[Option[Deletion]] =
    Codecs.serializer[Option[Deletion]]("deletion-option")
  given Serializer[RemoveTopic] = Codecs.serializer[RemoveTopic]("remove-topic")
  given fillTopicSettingsSerializer: Serializer[FillTopicSettings] =
    Codecs.serializer[FillTopicSettings]("fill-topic-settings")
  given filledTopicsSerializer: Serializer[FilledTopics] =
    Codecs.serializer[FilledTopics]("filled-topics")
  given Serializer[Map[String, DeclaredTopic]] =
    Codecs.serializer[Map[String, DeclaredTopic]]("declared-topics")
  given declareBrokerSerializer: Serializer[DeclareBroker] =
    Codecs.serializer[DeclareBroker]("declare-broker")
  given removeBrokerSerializer: Serializer[RemoveBroker] =
    Codecs.serializer[RemoveBroker]("remove-broker")
  // Named: an anonymous given of `Map[String, DeclaredBroker]` erases to the declared topics' one.
  given declaredBrokersSerializer: Serializer[Map[String, DeclaredBroker]] =
    Codecs.serializer[Map[String, DeclaredBroker]]("declared-brokers")

  given requestRehearsalSerializer: Serializer[RequestRehearsal] =
    Codecs.serializer[RequestRehearsal]("request-rehearsal")
  given observeRehearsalSerializer: Serializer[ObserveRehearsal] =
    Codecs.serializer[ObserveRehearsal]("observe-rehearsal")
  given rehearsalsSerializer: Serializer[Map[String, Rehearsal]] =
    Codecs.serializer[Map[String, Rehearsal]]("project-rehearsals")
  given reissueSerializer: Serializer[ReissueBackupCredential] =
    Codecs.serializer[ReissueBackupCredential]("reissue-backup-credential")
  given setDatabaseSerializer: Serializer[SetDatabase] =
    Codecs.serializer[SetDatabase]("set-database")
  given databaseSettingSerializer
      : Serializer[com.thinkmorestupidless.ankka.controlplane.api.DatabaseSetting] =
    Codecs.serializer[com.thinkmorestupidless.ankka.controlplane.api.DatabaseSetting](
      "project-database"
    )
  given requestRestoreSerializer: Serializer[RequestRestore] =
    Codecs.serializer[RequestRestore]("request-restore")
  given observeRestoreSerializer: Serializer[ObserveRestore] =
    Codecs.serializer[ObserveRestore]("observe-restore")
  given restoresSerializer: Serializer[Map[String, Restore]] =
    Codecs.serializer[Map[String, Restore]]("project-restores")
  given projectHistorySerializer: Serializer[Vector[ProjectHistoryEntry]] =
    Codecs.serializer[Vector[ProjectHistoryEntry]]("project-history")

  given makeGrantSerializer: Serializer[MakeGrant]  = Codecs.serializer[MakeGrant]("make-grant")
  given grantSerializer: Serializer[Grant]          = Codecs.serializer[Grant]("grant")
  given grantsSerializer: Serializer[Vector[Grant]] = Codecs.serializer[Vector[Grant]]("grants")
  given recordGrantChangeSerializer: Serializer[RecordGrantChange] =
    Codecs.serializer[RecordGrantChange]("record-grant-change")
  given receivedGrantsSerializer: Serializer[Vector[ReceivedGrant]] =
    Codecs.serializer[Vector[ReceivedGrant]]("received-grants")

  def create(context: EventSourcedEntityContext) = new ProjectEntity(context)

  val createProject = command("create")(_.create)
  val rename        = command("rename")(_.rename)
  val delete        = command("delete")(_.delete)
  val get           = query("get")(_.get)
  val exists        = query("exists")(_.exists)
  val registry      = query("registry")(_.registry)

  val configureRegistry = command("configure-registry")(_.configureRegistry)
  val clearRegistry     = command("clear-registry")(_.clearRegistry)

  val setSecretEntries  = command("set-secret-entries")(_.setSecretEntries)
  val removeSecretEntry = command("remove-secret-entry")(_.removeSecretEntry)
  val secrets           = query("secrets")(_.secrets)

  val declareTopic = command("declare-topic")(_.declareTopic)
  val removeTopic  = command("remove-topic")(_.removeTopic)
  val topics       = query("topics")(_.topics)

  val fillTopicSettings = command("fill-topic-settings")(_.fillTopicSettings)

  val declareBroker = command("declare-broker")(_.declareBroker)
  val removeBroker  = command("remove-broker")(_.removeBroker)
  val brokers       = query("brokers")(_.brokers)

  given setProjectLocationSerializer: Serializer[SetProjectLocation] =
    Codecs.serializer[SetProjectLocation]("set-project-location")
  val setLocation    = command("set-location")(_.setLocation)
  val bucketLocation = query("bucket-location")(_.bucketLocation)
  val requestRestore = command("request-restore")(_.requestRestore)
  val observeRestore = command("observe-restore")(_.observeRestore)
  val restores       = query("restores")(_.restores)
  val history        = query("history")(_.history)

  val setDatabase = command("set-database")(_.setDatabase)

  val reissueBackupCredential = command("reissue-backup-credential")(_.reissueBackupCredential)
  val backupCredentialGeneration =
    query("backup-credential-generation")(_.backupCredentialGeneration)

  val requestRehearsal = command("request-rehearsal")(_.requestRehearsal)
  val observeRehearsal = command("observe-rehearsal")(_.observeRehearsal)
  val rehearsals       = query("rehearsals")(_.rehearsals)
  val database         = query("database")(_.database)

  // Cross-project access (feature 040).
  val makeGrant         = command("make-grant")(_.makeGrant)
  val withdrawGrant     = command("withdraw-grant")(_.withdrawGrant)
  val revokeGrant       = command("revoke-grant")(_.revokeGrant)
  val acceptGrant       = command("accept-grant")(_.acceptGrant)
  val declineGrant      = command("decline-grant")(_.declineGrant)
  val relinquishGrant   = command("relinquish-grant")(_.relinquishGrant)
  val lapseGrant        = command("lapse-grant")(_.lapseGrant)
  val recordGrantChange = command("record-grant-change")(_.recordGrantChange)
  val grants            = query("grants")(_.grants)
  val receivedGrants    = query("received-grants")(_.receivedGrants)
  val deletion          = query("deletion")(_.deletion)
