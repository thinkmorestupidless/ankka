package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient as JavaClient
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage
import com.thinkmorestupidless.ankka.javasdk.component.{EntityContext, EventContext}
import com.thinkmorestupidless.ankka.sdk.CommandContext
import com.thinkmorestupidless.ankka.sdk

import java.util.Optional

/**
 * What a Java component reads, held for it by its host: the state, the command in hand and the
 * instance's identity. The Java base class takes its cell in its field initialiser, from the one
 * the bridge set on this thread while constructing it, so no setter is ever on the Java surface.
 */
final class EntityCell private[bridge] (
    private[bridge] val entityId: String,
    private[bridge] val componentId: String,
    private[bridge] val client: JavaClient
):
  private[bridge] var state: Any                      = null
  private[bridge] var stateSet: Boolean               = false
  private[bridge] var command: Option[CommandContext] = None
  private[bridge] var event: Option[sdk.EventContext] = None
  private val context                                 = CellContext(this)

  /** A workflow's steps, which its effects resolve step references through. */
  private[bridge] var steps: StepIndex = StepIndex.empty

  /** What a workflow's steps may call besides components. */
  private[bridge] var serviceClients: ServiceClients = Services.unavailable
  private[bridge] var secretStore: SecretStore       = Stores.noSecrets
  private[bridge] var objectStorage: ObjectStorage   = Stores.noStorage

  def services(): ServiceClients = serviceClients
  def secrets(): SecretStore     = secretStore
  def storage(): ObjectStorage   = objectStorage
  private lazy val workflow      = JavaWorkflowEffects(steps)
  private lazy val step          = JavaStepEffects(steps)

  /** A workflow's command effects; read by the Java base class. */
  def workflowEffects(): AnyRef = workflow

  /** A workflow's step effects; read by the Java base class. */
  def stepEffects(): AnyRef = step

  /** The state; read by the Java base class. */
  def currentState(): Any =
    if !stateSet then
      throw IllegalStateException("currentState() is only available inside a handler or applyEvent")
    state

  /** Where the event being applied was written; read by the Java base class. */
  def eventContext(): EventContext = event match
    case Some(e) => EventFacts(e)
    case None    =>
      throw IllegalStateException("eventContext() is only available inside applyEvent")

  /** The instance's context; read by the Java base class. */
  def entityContext(): EntityContext = context

object EntityCell:
  private val constructing = ThreadLocal[EntityCell]()

  /** The cell for the instance being constructed on this thread, or an unbound one. */
  def take(): EntityCell =
    val cell = constructing.get()
    if cell == null then EntityCell("", "", Clients.unavailable)
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: EntityCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()

private final class EventFacts(e: sdk.EventContext) extends EventContext:
  def installation(): Optional[String] =
    e.installation.fold(Optional.empty[String]())(Optional.of(_))
  def isLocal(): Boolean              = e.isLocal
  def sequenceNumber(): Long          = e.sequenceNumber
  def attribution(): Optional[String] =
    e.attribution.fold(Optional.empty[String]())(Optional.of(_))
  def forwardedFrom(): Optional[String] =
    e.forwardedFrom.fold(Optional.empty[String]())(Optional.of(_))

private final class CellContext(cell: EntityCell) extends EntityContext:
  private def command: CommandContext =
    cell.command.getOrElse(
      throw IllegalStateException("the command's context is only available inside a handler")
    )

  def entityId(): String                      = cell.entityId
  def componentId(): String                   = cell.componentId
  def componentClient(): JavaClient           = cell.client
  def metadata(key: String): Optional[String] = optional(command.metadata.get(key))
  def sequenceNumber(): Long                  = command.sequenceNumber
  def attribution(): Optional[String]         = optional(command.attribution)
  def installation(): Optional[String]        = optional(command.installation)
  def isHome(): Boolean                       = command.isHome
  def forwardedFrom(): Optional[String]       = optional(command.forwardedFrom)

  private def optional(o: Option[String]): Optional[String] =
    o.fold(Optional.empty[String]())(Optional.of(_))
