package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata}

/**
 * What every component is handed when the runtime instantiates it.
 *
 * `componentClient` lives here rather than being injected by a container because ankka has no
 * container: a component is constructed by its own companion, and anything it needs has to arrive
 * through this context.
 */
trait ComponentContext:
  def componentId: ComponentId
  def componentClient: ComponentClient

/** Context for a component addressed by instance id. */
trait EntityContext extends ComponentContext:
  def entityId: EntityId

trait EventSourcedEntityContext extends EntityContext
trait KeyValueEntityContext     extends EntityContext

/** Context for a workflow instance. */
trait WorkflowContext extends ComponentContext:
  def workflowId: EntityId

  /**
   * The service's secret store, for a step. A workflow's command handlers share this context, and a
   * call made from one is refused: it would put a blocking read on the workflow's single-writer
   * path, as an entity's would.
   */
  def secrets: SecretStore

  /**
   * Other services, called as this one, for a step. A workflow's command handlers share this
   * context, and a call made from one is refused, as its secret store's is: it would block the
   * workflow's other commands behind another service.
   */
  def services: ServiceClients

  /**
   * The service's buckets, for a step (feature 055). A workflow's command handlers share this
   * context, and a call made from one is refused, as its secret store's is: keeping or reading an
   * object is a blocking call the workflow's single-writer path must not make.
   */
  def storage: ObjectStorage = ObjectStorage.unavailable

/** Context for a view's projection. */
trait ViewComponentContext extends ComponentContext

/** Context for a consumer. */
trait ConsumerContext extends ComponentContext:
  /** The service's secret store. */
  def secrets: SecretStore

  /**
   * Other services, called as this one. A call is made before the change is done with, so a call
   * that fails has the change delivered again.
   */
  def services: ServiceClients

  /**
   * The service's buckets (feature 055). A call is made before the change is done with, so a keep
   * that fails has the change delivered again.
   */
  def storage: ObjectStorage = ObjectStorage.unavailable

/**
 * Available for the duration of a single command. Distinct from `EntityContext` because metadata
 * belongs to the request, not to the component.
 */
trait CommandContext:
  def entityId: EntityId
  def componentId: ComponentId
  def metadata: Metadata

  /** Sequence number of the last persisted event; 0 before anything is persisted. */
  def sequenceNumber: Long

  /**
   * The installation handling the command, for a replicated entity (feature 051); `None` on a
   * service that names no installations.
   */
  def installation: Option[String] = None

  /**
   * Whether this installation is the replicated entity's home. A command that may persist runs only
   * at the home, so this is `false` only for a query answered by a follower; always `true` for an
   * entity that is not replicated.
   */
  def isHome: Boolean = true

/**
 * Available inside `applyEvent`, for the event being applied (feature 051): where it was written,
 * on replay as well as when it is persisted.
 */
trait EventContext:
  /**
   * The installation that wrote the event; `None` for an entity on a service that names no
   * installations. An event written before the entity was replicated reads as written at its home.
   */
  def installation: Option[String]

  /** Whether the event was written at this installation rather than mirrored from another. */
  def isLocal: Boolean

  /** The event's sequence number in the entity's journal, the same at every installation. */
  def sequenceNumber: Long

private[ankka] final case class SimpleEventContext(
    installation: Option[String],
    isLocal: Boolean,
    sequenceNumber: Long
) extends EventContext

private[ankka] final case class SimpleEntityContext(
    entityId: EntityId,
    componentId: ComponentId,
    componentClient: ComponentClient
) extends EventSourcedEntityContext
    with KeyValueEntityContext

private[ankka] final case class SimpleWorkflowContextImpl(
    workflowId: EntityId,
    componentId: ComponentId,
    componentClient: ComponentClient,
    secrets: SecretStore,
    services: ServiceClients,
    override val storage: ObjectStorage = ObjectStorage.unavailable
) extends WorkflowContext

private[ankka] final case class SimpleViewContext(
    componentId: ComponentId,
    componentClient: ComponentClient
) extends ViewComponentContext

private[ankka] final case class SimpleConsumerContext(
    componentId: ComponentId,
    componentClient: ComponentClient,
    secrets: SecretStore,
    services: ServiceClients,
    override val storage: ObjectStorage = ObjectStorage.unavailable
) extends ConsumerContext

private[ankka] final case class SimpleCommandContext(
    entityId: EntityId,
    componentId: ComponentId,
    metadata: Metadata,
    sequenceNumber: Long,
    override val installation: Option[String] = None,
    override val isHome: Boolean = true
) extends CommandContext
