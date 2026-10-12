package com.thinkmorestupidless.ankka.testkit.views

import com.thinkmorestupidless.ankka.core.ComponentId
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.KeyedViewTestKit

/** A keyed view's handlers, tested with no runtime: what `KeyedViewTestKit` promises. */
class KeyedViewTestKitSuite extends munit.FunSuite:

  private def script(ops: Scripts.Op*): Recorded.Ran =
    Recorded.Ran(Scripts.add(Scripts.Script("noted", ops.toVector)))

  test("a change of either source writes the rows its handler names") {
    val kit = KeyedViewTestKit(Shipments)
    kit.change(Shipments.shipments, "s1", script(Scripts.Op.Touch(Vector("s1"), Some("c1"))))
    kit.change(Shipments.shipments, "s2", script(Scripts.Op.Touch(Vector("s2"), Some("c1"))))
    kit.answering(Shipments.ofCustomer)(values =>
      kit.rows.values.filter(_.customer.contains(values("customer"))).toVector
    )
    kit.change(Shipments.customers, "c1", script(Scripts.Op.TouchHolding("c1")))
    assertEquals(kit.row("s1").map(_.notes), Some(Vector("noted", "noted")))
    assertEquals(kit.row("s2").map(_.notes), Some(Vector("noted", "noted")))
  }

  test(
    "a row is moved by deleting its old key and writing its new one, and nothing else is deleted"
  ) {
    val kit = KeyedViewTestKit(Shipments)
    kit.change(Shipments.shipments, "s1", script(Scripts.Op.Touch(Vector("s1", "s2"))))
    kit.change(
      Shipments.shipments,
      "s1",
      script(Scripts.Op.Delete(Vector("s1")), Scripts.Op.Touch(Vector("s9")))
    )
    assertEquals(kit.rows.keySet, Set("s2", "s9"))
  }

  test("rows round-trip through the view's serializer, which refuses an unwritable row") {
    val kit = KeyedViewTestKit(Shipments)
    intercept[IllegalArgumentException](
      kit.change(Shipments.shipments, "s1", script(Scripts.Op.Unwritable("s1")))
    )
    assertEquals(kit.rows, Map.empty)
  }

  test("a query the test has not answered fails, naming it") {
    val kit = KeyedViewTestKit(Shipments)
    val thrown = intercept[IllegalStateException](
      kit.change(Shipments.customers, "c1", script(Scripts.Op.TouchHolding("c1")))
    )
    assert(thrown.getMessage.contains("'of-customer'"), thrown.getMessage)
  }

  test("a view declaring a refused statement fails the test that builds its kit") {
    object Broken
        extends KeyedView.Companion[ShipmentsView, ShipmentRow](
          ComponentId("broken"),
          ShipmentRow.serializer
        ):
      val shipments = source(ChangeSource.eventsOf(Shipment))(_.run)
      val other     = query("other")("SELECT payload FROM ankka_view_accounts")
      def create(ctx: ViewComponentContext) = new ShipmentsView(ctx.componentId.toString)
    val thrown = intercept[IllegalArgumentException](KeyedViewTestKit(Broken))
    assert(thrown.getMessage.contains("ankka_view_accounts"), thrown.getMessage)
  }

  test("a source that is not the view's is refused") {
    val kit   = KeyedViewTestKit(Shipments)
    val other = ThreeSourced.suppliers.asInstanceOf[KeyedSource[ShipmentsView, ShipmentRow]]
    intercept[IllegalArgumentException](kit.change(other, "x", script()))
  }

  // ── A keyed view of a workflow (spec 046) ─────────────────────────────────

  test("the component test kit hands a view a workflow change in every language") {
    // Scala's view of one source has no unit kit of its own, so the Scala row of the outline is a
    // keyed view of the workflow: the change it is handed carries the standing the test gave.
    val failed = WorkflowLifecycle("Failed", None, Map.empty, Some("declined"))
    val kit    = KeyedViewTestKit(WorkflowStandings)
    kit.change(
      WorkflowStandings.checkouts,
      "c1",
      FlowState("c1", "compensate", "refunded", Some("declined")),
      standing = Some(failed)
    )
    assertEquals(kit.row("c1"), Some(StandingRow("c1", "Failed", Some("declined"))))
  }

  test("a change handed with no standing carries none") {
    val kit = KeyedViewTestKit(WorkflowStandings)
    kit.change(WorkflowStandings.checkouts, "c2", FlowState("c2", "end", "charge", None))
    assertEquals(kit.row("c2"), Some(StandingRow("c2", "none", None)))
  }

final case class StandingRow(id: String, standing: String, failure: Option[String])

final class WorkflowStandingsView extends KeyedView[StandingRow]:
  def onCheckout(@scala.annotation.unused state: FlowState, change: Change): Effect =
    effects.updateRow(
      change.subject,
      StandingRow(
        change.subject,
        change.standing.fold("none")(_.status),
        change.standing.flatMap(_.failure)
      )
    )

object WorkflowStandings
    extends KeyedView.Companion[WorkflowStandingsView, StandingRow](
      ComponentId("workflow-standings"),
      com.thinkmorestupidless.ankka.core.Codecs.serializer[StandingRow]("standing-row")
    ):
  val checkouts                         = source(ChangeSource.stateOf(CheckoutFlow))(_.onCheckout)
  def create(ctx: ViewComponentContext) = new WorkflowStandingsView
