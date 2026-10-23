package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ServiceClients}
import com.thinkmorestupidless.ankka.javasdk.component.ChangeContext as JavaChange
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage
import com.thinkmorestupidless.ankka.sdk.ChangeContext

import java.util.Optional

/**
 * What a Java view or consumer reads while it handles a change: the row (a view's) and the change's
 * context, set by its host before each call, and what a consumer may call. Handed to the
 * component's constructor through a thread-local, as an entity's cell is.
 */
final class ViewCell private[bridge] (
    client: ComponentClient = Clients.unavailable,
    serviceClients: ServiceClients = Services.unavailable,
    secretStore: SecretStore = Stores.noSecrets,
    objectStorage: ObjectStorage = Stores.noStorage
):
  private var current: Any                  = null
  private var change: Option[ChangeContext] = None

  private[bridge] def set(row: Option[Any], context: Option[ChangeContext]): Unit =
    current = row.orNull
    change = context

  def row(): Any = current

  def context(): JavaChange = change match
    case Some(ctx) => JavaChangeContext(ctx)
    case None =>
      throw IllegalStateException("the change's context is only available while handling one")

  def componentClient(): ComponentClient = client

  def services(): ServiceClients = serviceClients

  def secrets(): SecretStore = secretStore

  def storage(): ObjectStorage = objectStorage

object ViewCell:
  private val constructing = ThreadLocal[ViewCell]()

  def take(): ViewCell =
    val cell = constructing.get()
    if cell == null then ViewCell()
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: ViewCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()

private final case class JavaChangeContext(ctx: ChangeContext) extends JavaChange:
  def subject(): String      = ctx.subject
  def sequenceNumber(): Long = ctx.sequenceNumber
  def localOrigin(): Boolean = ctx.localOrigin
  def installation(): Optional[String] =
    ctx.installation.fold(Optional.empty[String]())(Optional.of(_))
