package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.cart.{CartEvent, LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.component.GraphDelta
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.*
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * The Java component kits for the kinds an entity kit does not cover: the checkout workflow to its
 * pause and on, a view's rows, a keyed view that asks its own query, a consumer's published
 * messages and a graph consumer's deltas, and the stubbed client a kit's component calls.
 */
class KindKitsSuite extends munit.FunSuite with LogCapturing:

  private def added(product: String) = CartEvent.ItemAdded(LineItem(product, product, 1))

  // The Java row of features/polyglot/testing.feature, which no Gherkin suite runs: this is the
  // test named after it.
  test("the component test kit follows a workflow to where it waits for a command (Java)") {
    val kit     = WorkflowTestKit.of(classOf[Checkout], "c-1")
    val started = kit.call(KindRefs.startCheckout, Checkout.Start("cart-1", 60000))
    assertEquals(started.reply(), Done.DONE)
    assertEquals(started.steps().asScala.toVector, Vector("reserve", "charge"))
    assert(started.lifecycle().isPaused, started.lifecycle().toString)
    assertEquals(started.pausedFor().toScala, Some(Duration.ofMinutes(1)))
    assertEquals(started.onTimeout().toScala, Some("expire"))
    assertEquals(started.state().status, "awaiting")
    val confirmed = kit.call(KindRefs.confirmCheckout)
    assertEquals(confirmed.steps().asScala.toVector, Vector("finish"))
    assert(confirmed.lifecycle().isCompleted)
    assertEquals(
      kit.state().log.asScala.toVector,
      Vector("started", "reserved", "awaiting", "confirmed", "done")
    )
  }

  test("a pause's timeout runs its step when the test says so, and a refusal changes nothing") {
    val kit   = WorkflowTestKit.of(classOf[Checkout], "c-2")
    val early = kit.call(KindRefs.confirmCheckout)
    assertEquals(early.error(), "nothing to confirm")
    assertEquals(kit.lifecycle().status, "NotStarted")
    kit.call(KindRefs.startCheckout, Checkout.Start("cart-2", 1000)): Unit
    val expired = kit.timeOut()
    assertEquals(expired.steps().asScala.toVector, Vector("expire"))
    assertEquals(kit.state().status, "expired")
  }

  test("a step that throws is retried, then failed over, as the workflow's settings say") {
    val kit    = WorkflowTestKit.of(classOf[Checkout], "c-3")
    val result = kit.call(KindRefs.startCheckout, Checkout.Start("big-cart", 1000))
    assertEquals(result.steps().asScala.toVector, Vector("reserve", "flaky", "flaky", "refund"))
    assertEquals(result.lifecycle().retries.asScala.toMap, Map("flaky" -> Integer.valueOf(1)))
    assertEquals(kit.state().status, "refunded")
  }

  test("a deleted workflow says so") {
    val kit = WorkflowTestKit.of(classOf[Checkout], "c-4")
    kit.call(KindRefs.startCheckout, Checkout.Start("cart-4", 1000)): Unit
    kit.call(KindRefs.cancelCheckout): Unit
    assert(kit.deleted())
  }

  test("a view's handler is given its subject's row, and the row it writes is read back") {
    val kit = ViewTestKit.of(classOf[CartRows])
    kit.change("cart-1", added("socks"))
    kit.change("cart-1", added("hats"))
    kit.change("cart-1", CartEvent.CheckedOut())
    kit.change("cart-2", added("socks"))
    kit.change("cart-2", CartEvent.Discarded())
    assertEquals(kit.row("cart-1").toScala, Some(CartRows.Row("cart-1", 2, true)))
    assertEquals(kit.row("cart-2").toScala, Some(CartRows.Row("cart-2", 1, false)))
    assertEquals(kit.rows().keySet.asScala.toSet, Set("cart-1", "cart-2"))
    kit.deleted("cart-2")
    assertEquals(kit.row("cart-2").toScala, None)
  }

  test("a keyed view's handlers write rows by key, deletions remove them") {
    val kit = KeyedViewTestKit.of(classOf[ProductCarts])
    kit.change(classOf[ShoppingCart], "cart-1", added("socks"))
    kit.change(classOf[ShoppingCart], "cart-2", added("socks"))
    kit.change(classOf[TreeNode], "n1", TreeNode.Placed("root", ""))
    assertEquals(kit.row("socks").toScala, Some(ProductCarts.Row("socks", 2)))
    assertEquals(kit.row("node:n1").toScala, Some(ProductCarts.Row("root", 0)))
    kit.deleted(classOf[TreeNode], "n1")
    assertEquals(kit.row("node:n1").toScala, None)
    val refused =
      intercept[IllegalArgumentException](kit.change(classOf[Checkout], "c", Checkout.Charge(1)))
    assert(refused.getMessage.contains("not a source"), refused.getMessage)
  }

  test("a keyed view's query is answered as the test says, and one nobody answered fails") {
    val kit = KeyedViewTestKit.of(classOf[CartCounts])
    intercept[IllegalStateException](kit.change(classOf[ShoppingCart], "cart-1", added("socks")))
    kit.answering(
      CartCounts.OF_PRODUCT,
      values => kit.row(values.get("product")).toScala.toList.asJava
    )
    kit.change(classOf[ShoppingCart], "cart-1", added("socks"))
    kit.change(classOf[ShoppingCart], "cart-2", added("socks"))
    assertEquals(kit.row("socks").toScala, Some(CartCounts.Row("socks", 2)))
  }

  test("a consumer's messages are read back as a broker would be given them") {
    val kit    = ConsumerTestKit.of(classOf[CartLines])
    val result = kit.onMessage(CartEvent.CheckedOut(), "cart-1", 3)
    assertEquals(
      result.payloads().asScala.toVector,
      Vector(CartLines.Line("cart-1", "checked out"), CartLines.Line("cart-1", "thanks"))
    )
    assertEquals(
      result.recordKeys().asScala.toVector.map(_.toScala),
      Vector(Some("checkouts"), Some("receipts"))
    )
    val receipt = result.messages().get(1)
    assertEquals(receipt.metadata.get("ce-channel"), "email")
    assertEquals(receipt.metadata.get("ce-subject"), "cart-1")
    assertEquals(receipt.text, """{"cartId":"cart-1","what":"thanks"}""")
    assertEquals(kit.onMessage(added("socks")).recordKeys().get(0).toScala, Some("test"))
  }

  test("a consumer's calls go to the stubbed client, and one nobody stubbed is refused") {
    val placed = ConcurrentLinkedQueue[TreeNode.Placed]()
    val stubs  = TestClient
      .of(classOf[TreeNode])
      .stub(
        KindRefs.place,
        (p: TreeNode.Placed) =>
          placed.add(p)
          Done.DONE
      )
    val kit = ConsumerTestKit.of(classOf[Placements], stubs)
    assertEquals(
      kit.onMessage(Placements.Placement("n1", "root", "")).payloads().asScala.toVector,
      Vector.empty
    )
    assertEquals(placed.asScala.toVector, Vector(TreeNode.Placed("root", "")))
    val unstubbed = ConsumerTestKit.of(classOf[Placements])
    intercept[CommandException](unstubbed.onMessage(Placements.Placement("n2", "leaf", "n1")))
    val refusing = ConsumerTestKit.of(
      classOf[Placements],
      TestClient.of(classOf[TreeNode]).refuse(KindRefs.place, Problem.conflict("taken"))
    )
    val refused =
      intercept[CommandException](refusing.onMessage(Placements.Placement("n3", "x", "")))
    assertEquals(refused.problem().status(), 409)
  }

  test("a graph consumer's deltas are read as the merge sink reads them") {
    val kit    = ConsumerTestKit.graph(classOf[TreeGraph])
    val deltas = kit.onMessage(TreeNode.Placed("leaf", "n1"), "n2", 1).asScala.toVector
    assertEquals(
      deltas.map(_.key),
      Vector(GraphDelta.nodeKey("n2"), GraphDelta.edgeKey("n2-under"))
    )
    assertEquals(deltas(0).properties.get("tags"), java.util.List.of("a", "b"))
    assertEquals(deltas(1).from.toScala, Some("n2"))
    val tombstone = kit.onDelete("n2").get(0)
    assert(tombstone.isTombstone)
    // The serializer writes what the reader reads back, under the key the sink checks.
    val bytes = GraphDelta.serializer().toBytes(deltas(0))
    assertEquals(GraphDelta.read(deltas(0).key, bytes), deltas(0))
    val wrongKey = intercept[IllegalArgumentException](GraphDelta.read("node:other", bytes))
    assert(wrongKey.getMessage.contains("not this delta's element key"), wrongKey.getMessage)
  }

  test("a kit for a class of another kind is refused, naming the kind") {
    val refused = intercept[IllegalArgumentException](
      ViewTestKit.of(classOf[ProductCarts].asInstanceOf[Class[CartRows]])
    )
    assert(refused.getMessage.contains("is not a view"), refused.getMessage)
  }
