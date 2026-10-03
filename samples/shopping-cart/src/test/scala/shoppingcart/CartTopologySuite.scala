package shoppingcart

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json}
import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentRegistry}
import com.thinkmorestupidless.ankka.runtime.{CallCounts, TopologyJson}
import shoppingcart.application.*

import java.nio.file.{Files, Path}

/**
 * What the cart declares it is connected to, written out by hand.
 *
 * The topology says a service's declared connections are complete: every view's and every
 * consumer's source, and every topic a consumer publishes to. This is that claim checked against a
 * real service, and it is kept honest in both directions. The connections are compared for equality
 * with the list here, so one that goes missing and one that is invented both fail. And the
 * components are the ones `Main` registers, read out of its source, so a view or a consumer added
 * to the cart fails this suite until its connection is on the list.
 */
class CartTopologySuite extends munit.FunSuite:

  /** Every component the cart can register, by the name `Main` registers it under. */
  private val components: Map[String, ComponentDescriptor] = Map(
    "ShoppingCartEntity" -> ShoppingCartEntity.descriptor,
    "CheckoutLog"        -> CheckoutLog.descriptor,
    "CheckoutWorkflow"   -> CheckoutWorkflow.descriptor,
    "CartRows"           -> CartRows.descriptor,
    "CheckoutNotifier"   -> CheckoutNotifier.descriptor,
    "CartAssistant"      -> CartAssistant.descriptor,
    "CartAnswerer"       -> CartAnswerer.descriptor,
    "CartGraph"          -> CartGraph.descriptor,
    "CartContentsGraph"  -> CartContentsGraph.descriptor
  )

  private def registeredByMain: Set[String] =
    val main = Files.readString(Path.of("src/main/scala/Main.scala"))
    """\.register\((\w+)\.descriptor\)""".r.findAllMatchIn(main).map(_.group(1)).toSet

  private def declared: Set[(String, String, String)] =
    val registry =
      ComponentRegistry.fromOrThrow(components.values.toSeq ++ AgentRuntime.descriptors)
    val document =
      Json
        .parse(
          TopologyJson.render(
            "cart",
            "1",
            "2026-10-01T09:12:03Z",
            registry,
            Vector.empty,
            CallCounts.Snapshot.empty(600, 0L),
            _ => None
          )
        )
        .fold(problem => fail(s"the topology is not JSON: $problem"), identity)
    def string(of: Json, field: String) = of(field).flatMap(_.asString).getOrElse(fail(s"$of"))
    document("declared")
      .flatMap(_.asArray)
      .getOrElse(fail("the topology has no declared connections"))
      .map(edge => (string(edge, "from"), string(edge, "to"), string(edge, "kind")))
      .toSet

  test("this suite knows every component Main registers") {
    assertEquals(registeredByMain, components.keySet)
  }

  test("the cart's declared connections are exactly these") {
    assertEquals(
      declared,
      Set(
        ("shopping-cart", "cart-rows", "events"),
        ("shopping-cart", "checkout-notifier", "events"),
        ("checkout-notifier", "topic:cart-checkouts", "topic-publication"),
        // Two graph consumers of the cart's events, both publishing to one topic.
        ("shopping-cart", "cart-graph", "events"),
        ("cart-graph", "topic:cart-graph", "topic-publication"),
        ("shopping-cart", "cart-contents-graph", "events"),
        ("cart-contents-graph", "topic:cart-graph", "topic-publication"),
        // The agent runtime's own: its cascade follows its tasks.
        ("ankka-task", "ankka-task-cascade", "events")
      )
    )
  }
