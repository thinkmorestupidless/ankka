package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, CompactionSettings}
import com.thinkmorestupidless.ankka.core.{DeclaredHandler, HandlerKind}
import munit.FunSuite

/**
 * What a descriptor says about its component without anyone knowing its type: the handlers it
 * declares, and whether it is one of the platform's own. A service's topology is built from these,
 * and a name a caller sends is believed only when it is one of them.
 */
final class ComponentDescriptorsSuite extends FunSuite:

  private def declared(handlers: Vector[DeclaredHandler]): Vector[(String, HandlerKind)] =
    handlers.map(h => h.name -> h.kind)

  test("every component the agent runtime registers for itself says it is the platform's") {
    val descriptors = AgentRuntime.descriptors
    assertEquals(
      descriptors.map(_.componentId.toString).toSet,
      Set(
        "ankka-session-memory",
        "ankka-task",
        "ankka-agent-instance",
        "ankka-task-cascade",
        "ankka-approval-expiry",
        // Blueprints: the blueprint and run records, the one ask agent and the one worker every
        // step goes to, the runs view and the schedule's timer.
        "ankka-blueprint",
        "ankka-run",
        "ankka-blueprint-ask",
        "ankka-blueprint-worker",
        "ankka-blueprint-runs",
        "ankka-blueprint-schedule"
      )
    )
    descriptors.foreach(d => assert(d.platform, s"$d should be a platform component"))
  }

  test("the session compactor, registered only with compaction, is the platform's too") {
    val descriptors =
      AgentRuntime().withCompaction(CompactionSettings(), Some(_ => "summary")).descriptors
    assertEquals(descriptors.size, 12, descriptors.toString)
    descriptors.foreach(d => assert(d.platform, s"$d should be a platform component"))
  }

  test("a service's own components are not the platform's") {
    val own = Vector(
      ProfileEntity.descriptor,
      TransferWorkflow.descriptor,
      StockLevels.descriptor,
      LowStockNotifier.descriptor,
      OrderTimers.descriptor,
      WeatherAgent.descriptor
    )
    own.foreach(d => assert(!d.platform, s"$d is a service's own"))
  }

  test("an entity declares its commands and its queries, in name order") {
    assertEquals(
      declared(ProfileEntity.descriptor.declaredHandlers),
      Vector(
        "close"        -> HandlerKind.Command,
        "expire-in"    -> HandlerKind.Command,
        "get"          -> HandlerKind.Query,
        "record-login" -> HandlerKind.Command,
        "register"     -> HandlerKind.Command,
        "rename"       -> HandlerKind.Command,
        "revision"     -> HandlerKind.Query
      )
    )
  }

  test("a workflow declares its steps beside its commands and queries") {
    assertEquals(
      declared(TransferWorkflow.descriptor.declaredHandlers),
      Vector(
        "compensate" -> HandlerKind.Step,
        "deposit"    -> HandlerKind.Step,
        "start"      -> HandlerKind.Command,
        "status"     -> HandlerKind.Query,
        "withdraw"   -> HandlerKind.Step
      )
    )
  }

  test("a view and a consumer each declare the one thing they do with a change") {
    assertEquals(
      declared(StockLevels.descriptor.declaredHandlers),
      Vector("on-change" -> HandlerKind.Update)
    )
    assertEquals(
      declared(LowStockNotifier.descriptor.declaredHandlers),
      Vector("on-message" -> HandlerKind.Update)
    )
  }

  test("a timed action declares what a timer can run") {
    assertEquals(
      declared(OrderTimers.descriptor.declaredHandlers).toSet,
      Set("always-fails" -> HandlerKind.Action, "expire-order" -> HandlerKind.Action)
    )
  }

  test("an agent declares its commands, and its streams as streams") {
    val handlers = declared(WeatherAgent.descriptor.declaredHandlers).toMap
    assertEquals(handlers.get("ask"), Some(HandlerKind.Command))
    assertEquals(handlers.get("chat"), Some(HandlerKind.Stream))
    assertEquals(handlers.get("chat-guarded"), Some(HandlerKind.Stream))
    assertEquals(handlers.keys.toVector.sorted, handlers.keys.toVector.sorted.distinct)
  }
