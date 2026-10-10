package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{
  BrokerDeclarationRequest,
  CreateProject,
  ProjectBrokers,
  ProjectDetail,
  ProjectSecretSummary,
  ProjectTopics,
  RegistrySummary,
  SetProjectLocation
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
    case _: ProjectDeleted                          => currentState.onDeleted
    case RegistryConfigured(server, username, secretName, actor, at) =>
      currentState.onRegistryConfigured(server, username, secretName, actor, at)
    case _: RegistryCleared => currentState.onRegistryCleared
    case ProjectSecretEntriesSet(name, entries, actor, at) =>
      currentState.onSecretEntriesSet(name, entries, actor, at)
    case ProjectSecretEntryRemoved(name, entry, _, _) =>
      currentState.onSecretEntryRemoved(name, entry)
    case ProjectTopicDeclared(name, partitions, _, at, compacted, contract) =>
      currentState.onTopicDeclared(name, partitions, at, compacted, contract)
    case ProjectBrokerDeclared(name, bootstrap, shape, secretName, _, at) =>
      currentState.onBrokerDeclared(name, DeclaredBroker(bootstrap, shape, secretName, at))
    case ProjectBrokerRemoved(name, _, _)   => currentState.onBrokerRemoved(name)
    case ProjectLocationSet(location, _, _) => currentState.onLocationSet(location)
    case ProjectTopicRemoved(name, _, _)    => currentState.onTopicRemoved(name)

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
   * Declare a topic on the project, or raise its partitions (feature 027). Its rules are the
   * project's own state, so every one is checked here: a name and a count the broker can hold, and
   * never fewer partitions than the project declares. The same count again records nothing.
   */
  def declareTopic(request: DeclareTopic): Effect[Done] =
    if !currentState.exists then notFound
    else
      val problems = ProjectTopics.problems(request.name, request.partitions)
      if problems.nonEmpty then effects.error(problems.mkString("; "))
      else
        currentState.topics.get(request.name) match
          case Some(has) if has.partitions > request.partitions =>
            effects.error(
              ProjectTopics.fewer(request.name, has.partitions, request.partitions),
              ErrorCode.Conflict
            )
          case Some(has)
              if has.partitions == request.partitions && has.compacted == request.compacted &&
                has.contract == request.contract =>
            effects.reply(Done)
          case _ =>
            effects
              .persist(
                ProjectTopicDeclared(
                  request.name,
                  request.partitions,
                  actor,
                  at,
                  request.compacted,
                  request.contract
                )
              )
              .thenReply(_ => Done)

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
  given Serializer[RemoveTopic]  = Codecs.serializer[RemoveTopic]("remove-topic")
  given Serializer[Map[String, DeclaredTopic]] =
    Codecs.serializer[Map[String, DeclaredTopic]]("declared-topics")
  given declareBrokerSerializer: Serializer[DeclareBroker] =
    Codecs.serializer[DeclareBroker]("declare-broker")
  given removeBrokerSerializer: Serializer[RemoveBroker] =
    Codecs.serializer[RemoveBroker]("remove-broker")
  // Named: an anonymous given of `Map[String, DeclaredBroker]` erases to the declared topics' one.
  given declaredBrokersSerializer: Serializer[Map[String, DeclaredBroker]] =
    Codecs.serializer[Map[String, DeclaredBroker]]("declared-brokers")

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

  val declareBroker = command("declare-broker")(_.declareBroker)
  val removeBroker  = command("remove-broker")(_.removeBroker)
  val brokers       = query("brokers")(_.brokers)

  given setProjectLocationSerializer: Serializer[SetProjectLocation] =
    Codecs.serializer[SetProjectLocation]("set-project-location")
  val setLocation    = command("set-location")(_.setLocation)
  val bucketLocation = query("bucket-location")(_.bucketLocation)
