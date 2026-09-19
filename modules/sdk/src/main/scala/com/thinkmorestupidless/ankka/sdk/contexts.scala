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

/** Context for a view's projection. */
trait ViewComponentContext extends ComponentContext

/** Context for a consumer. */
trait ConsumerContext extends ComponentContext

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

private[ankka] final case class SimpleEntityContext(
    entityId: EntityId,
    componentId: ComponentId,
    componentClient: ComponentClient
) extends EventSourcedEntityContext
    with KeyValueEntityContext

private[ankka] final case class SimpleWorkflowContextImpl(
    workflowId: EntityId,
    componentId: ComponentId,
    componentClient: ComponentClient
) extends WorkflowContext

private[ankka] final case class SimpleViewContext(
    componentId: ComponentId,
    componentClient: ComponentClient
) extends ViewComponentContext

private[ankka] final case class SimpleConsumerContext(
    componentId: ComponentId,
    componentClient: ComponentClient
) extends ConsumerContext

private[ankka] final case class SimpleCommandContext(
    entityId: EntityId,
    componentId: ComponentId,
    metadata: Metadata,
    sequenceNumber: Long
) extends CommandContext
