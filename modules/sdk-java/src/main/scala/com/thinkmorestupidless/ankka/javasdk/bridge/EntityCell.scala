package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient as JavaClient
import com.thinkmorestupidless.ankka.javasdk.component.EntityContext
import com.thinkmorestupidless.ankka.sdk.CommandContext

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
  private val context                                 = CellContext(this)

  /** The state; read by the Java base class. */
  def currentState(): Any =
    if !stateSet then
      throw IllegalStateException("currentState() is only available inside a handler or applyEvent")
    state

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
