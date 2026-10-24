package com.thinkmorestupidless.ankka.agent.javasdk.bridge

import com.thinkmorestupidless.ankka.agent.autonomous.AutonomousAgent
import com.thinkmorestupidless.ankka.javasdk.bridge.{Clients, Services, Stores}
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ServiceClients}
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage

import java.util.Optional
import scala.jdk.OptionConverters.*

/**
 * What a Java autonomous agent reads: its instance and what a tool may call. Handed to the agent's
 * constructor through a thread-local, as an agent's cell is; one built outside a host, to be read
 * for its definition, gets a cell that offers nothing.
 */
final class AutonomousCell private[bridge] (
    instance: String = "",
    client: ComponentClient = Clients.unavailable,
    serviceClients: ServiceClients = Services.unavailable,
    secretStore: SecretStore = Stores.noSecrets,
    objectStorage: ObjectStorage = Stores.noStorage
):
  def instanceId(): String               = instance
  def componentClient(): ComponentClient = client
  def services(): ServiceClients         = serviceClients
  def secrets(): SecretStore             = secretStore
  def storage(): ObjectStorage           = objectStorage

object AutonomousCell:
  private val constructing = ThreadLocal[AutonomousCell]()

  def take(): AutonomousCell =
    val cell = constructing.get()
    if cell == null then AutonomousCell()
    else
      constructing.remove()
      cell

  def currentTask(): Optional[String] = AutonomousAgent.currentTask.toJava

  private[bridge] def constructing[A](cell: AutonomousCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()
