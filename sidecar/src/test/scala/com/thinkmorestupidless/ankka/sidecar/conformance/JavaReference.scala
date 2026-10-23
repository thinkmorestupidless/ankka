package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.ankka.javasdk.bridge.{Acls, JavaServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.conformance.ConformanceEntry
import com.thinkmorestupidless.ankka.mcp.McpRuntime
import com.thinkmorestupidless.ankka.sdk.{ConsumerDescriptor, TimerScheduler}
import com.thinkmorestupidless.ankka.testkit.{ConsumerTestKit, TestTransport}

import scala.jdk.CollectionConverters.*

/**
 * A reference written through the Java SDK, as the in-process target hosts it: its classes read as
 * a Java service reads them, served by the same runtime the Scala reference is.
 *
 * The Java SDK has no agents yet (spec 058, T076), so the Scala reference's three agents and its
 * autonomous agent's routes are hosted beside it: discovery is held to the same components and
 * routes in every language, and the cases that drive an agent are reported as run outside this
 * reference (`ranOutside`) until the Java agents replace them.
 */
final class JavaReference(entry: ConformanceEntry) extends EmbeddedReference:

  entry.authenticateWith(Acls.fromScala(ConformanceTarget.authenticated))

  private val read = JavaServiceBuilder.read(
    entry.components.asScala.toVector.map(_ -> None),
    Map.empty,
    entry.endpoints.asScala.toVector,
    mcpEndpoints = entry.mcpEndpoints.asScala.toVector
  )

  private val agents: Seq[ComponentDescriptor] = Seq(
    ConformanceReference.Assistant.descriptor,
    ConformanceReference.Answerer.descriptor,
    ConformanceReference.Approver.descriptor
  )

  def language: String = entry.language

  // The target registers the MCP runtime's own components; the read adds them once more.
  def descriptors: Seq[ComponentDescriptor] =
    val platform = McpRuntime.descriptors.map(_.componentId).toSet
    read.descriptors.filterNot(d => platform(d.componentId)) ++ agents

  // Every route and MCP endpoint the Java classes declare, then the Scala autonomous agent's.
  def endpoints(
      timers: () => TimerScheduler,
      problems: () => Vector[String]
  ): Seq[EndpointClients => HttpEndpoint] =
    read.endpointFactories(() => read.timers(timers())) :+
      ((clients: EndpointClients) => ConformanceReference.AutonomousEndpoint(clients))

  // Served with the routes above, behind the ACL handed to the entry at construction.
  def mcpEndpoints(authenticated: Acl): Seq[EndpointClients => HttpEndpoint] =
    entry.authenticateWith(Acls.fromScala(authenticated))
    Seq.empty

  // Every case that drives an agent, whose components here are the Scala reference's.
  override def ranOutside: Map[String, String] =
    val why = "drives an agent, which the Java SDK does not have yet (spec 058, T076)"
    JavaReference.AgentCases.map(_ -> why).toMap

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

object JavaReference:

  /** The cases that drive an agent: request, autonomous, approval, and an agent's MCP tools. */
  val AgentCases: Vector[String] =
    Vector(
      "plans-and-replies",
      "names-a-model",
      "tool-invoked-in-process",
      "tool-error-continues",
      "stream",
      "guardrail-blocks",
      "session-survives-process-restart"
    ).map("agent." + _) ++
      Vector(
        "completes-with-typed-result",
        "fails-on-request",
        "malformed-result-is-tool-error",
        "tool-error-continues",
        "guardrail-fails-task",
        "budget-fails-task",
        "rule-check-fault-is-iteration-failure",
        "rule-rejects-then-accepts",
        "queue-runs-in-order",
        "suspend-resume",
        "terminate-unassigns",
        "cancel-queued",
        "dependency-result-in-context",
        "dependency-failure-cascades",
        "notifications-in-order",
        "notifications-no-replay",
        "state-of-idle-instance",
        "approval.waits-without-budget",
        "approval.approved-continues"
      ).map("auto." + _) ++
      Vector(
        "awaits",
        "approved-runs-once",
        "refused-tells-model",
        "decided-once",
        "session-waits",
        "needs-a-name"
      ).map("approval." + _) ++
      Vector(
        "mcp.tools-offered",
        "mcp.call-reaches-server",
        "mcp.server-approval",
        "mcp.result-guardrail-withholds",
        "svc.tool-calls-service"
      )
