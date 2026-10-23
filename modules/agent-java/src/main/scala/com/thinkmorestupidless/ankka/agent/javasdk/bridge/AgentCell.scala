package com.thinkmorestupidless.ankka.agent.javasdk.bridge

import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffects
import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, Services, Stores}
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ServiceClients}
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage

/**
 * What a Java agent reads: its effects, its session and what a tool may call. Handed to the agent's
 * constructor through a thread-local, as an entity's cell is; an agent built outside a host gets
 * one that refuses everything but the settings it is read for.
 */
final class AgentCell private[bridge] (
    effectsOf: () => AgentEffects = () => AgentCell.outside("effects()"),
    session: String = "",
    client: ComponentClient = Clients.unavailable,
    serviceClients: ServiceClients = Services.unavailable,
    secretStore: SecretStore = Stores.noSecrets,
    objectStorage: ObjectStorage = Stores.noStorage
):
  def effects(): AgentEffects            = effectsOf()
  def sessionId(): String                = session
  def componentClient(): ComponentClient = client
  def services(): ServiceClients         = serviceClients
  def secrets(): SecretStore             = secretStore
  def storage(): ObjectStorage           = objectStorage

object AgentCell:
  private val constructing = ThreadLocal[AgentCell]()

  def take(): AgentCell =
    val cell = constructing.get()
    if cell == null then AgentCell()
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: AgentCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()

  private def outside(what: String): Nothing =
    throw IllegalStateException(
      s"$what is only available to an agent a service or a test kit built, inside a handler"
    )
