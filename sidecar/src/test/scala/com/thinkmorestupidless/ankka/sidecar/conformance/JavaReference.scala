package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.agent.AgentRuntime
import com.thinkmorestupidless.ankka.agent.javasdk.{Agents, ModelProvider}
import com.thinkmorestupidless.ankka.agent.javasdk.bridge.ScalaModels
import com.thinkmorestupidless.ankka.agent.javasdk.conformance.AgentConformanceEntry
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.ankka.javasdk.bridge.{Acls, JavaServiceBuilder, Modules}
import com.thinkmorestupidless.ankka.javasdk.conformance.ConformanceEntry
import com.thinkmorestupidless.ankka.mcp.McpRuntime
import com.thinkmorestupidless.ankka.sdk.{ConsumerDescriptor, TimerScheduler}
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, TestTransport}

import scala.jdk.CollectionConverters.*

/**
 * A reference written through the Java SDK, as the in-process target hosts it: its classes read as
 * a Java service reads them, with the agent module, served by the same runtime the Scala reference
 * is. Its agents name the suite's `second` model through a Java handle registered for it.
 */
final class JavaReference(entry: ConformanceEntry) extends EmbeddedReference:

  entry.authenticateWith(Acls.fromScala(ConformanceTarget.authenticated))

  entry match
    case agents: AgentConformanceEntry =>
      val second = new ModelProvider:
        def name(): String = ConformanceReference.SecondModel.name
      ScalaModels.register(second, ConformanceReference.SecondModel)
      agents.secondModel(second)
    case _ => ()

  private val read = JavaServiceBuilder.read(
    entry.components.asScala.toVector.map(_ -> None),
    Map.empty,
    entry.endpoints.asScala.toVector,
    mcpEndpoints = entry.mcpEndpoints.asScala.toVector,
    modules = Vector(Modules.asJava(Agents.module()))
  )

  def language: String = entry.language

  // The target registers the MCP and agent runtimes' own components; the read adds them once more.
  def descriptors: Seq[ComponentDescriptor] =
    val platform = (McpRuntime.descriptors ++ AgentRuntime.descriptors).map(_.componentId).toSet
    read.descriptors.filterNot(d => platform(d.componentId))

  def endpoints(
      timers: () => TimerScheduler,
      problems: () => Vector[String]
  ): Seq[EndpointClients => HttpEndpoint] =
    read.endpointFactories(() => read.timers(timers()))

  // Served with the routes above, behind the ACL handed to the entry at construction.
  def mcpEndpoints(authenticated: Acl): Seq[EndpointClients => HttpEndpoint] =
    entry.authenticateWith(Acls.fromScala(authenticated))
    Seq.empty

  def readOnlyHandlers: Set[(String, String)] =
    entry.readOnlyHandlers.asScala.toSet.flatMap((component, handlers) =>
      handlers.asScala.map(component -> _)
    )

  private lazy val relayDescriptor =
    read.descriptors.collectFirst {
      case c: ConsumerDescriptor[?, ?, ?] if c.componentId.toString == "contract-relay" =>
        c.asInstanceOf[ConsumerDescriptor[
          com.thinkmorestupidless.ankka.sdk.Consumer[Any, Any],
          Any,
          Any
        ]]
    }.get

  // The reference's own message type, built and read by reflection: the adapter cannot name it.
  private lazy val fanned =
    Class.forName(
      entry.getClass.getPackageName + ".Fanned",
      true,
      entry.getClass.getClassLoader
    )

  def relay(n: Int): Option[Int] =
    val kit     = ConsumerTestKit.ofDescriptor(relayDescriptor, TestTransport.unroutedClient)
    val message = fanned.getDeclaredConstructor(classOf[Int]).newInstance(Int.box(n))
    kit
      .onMessage(message)
      .payloads
      .headOption
      .map(p => fanned.getMethod("n").invoke(p).asInstanceOf[Integer].intValue)
