package shoppingcart

import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.core.graph.GraphDelta
import com.thinkmorestupidless.ankka.runtime.{InMemoryPublisher, ProjectionRuntime}
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}
import shoppingcart.application.{CartGraph, ShoppingCartEntity}
import shoppingcart.domain.LineItem

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The carts as a graph, against a real journal: what is published to `cart-graph` as carts are
 * filled, checked out, discarded and started again.
 *
 * Each record is read back with the reader a consumer of the topic would use, under the key it was
 * published under — so what is asserted is what ankka-flow's merge sink would be given.
 */
class CartGraphSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 4.minutes

  private var testKit: AnkkaTestKit = null
  private val publisher             = InMemoryPublisher()

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CartGraph.descriptor),
      Seq(ProjectionRuntime.withPublisher(publisher))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def cart(id: String) = testKit.componentClient.forEventSourcedEntity(EntityId(id))

  /** The deltas published about one cart, in the order they were published. */
  private def published(cartId: String): Vector[GraphDelta] =
    publisher
      .publishedTo("cart-graph")
      .toVector
      .filter(_.metadata.subject.contains(cartId))
      .map(record =>
        GraphDelta.read(record.recordKey, record.payload).fold(problem => fail(problem), identity)
      )

  private def eventually[A](description: String, within: FiniteDuration = 30.seconds)(
      check: => Option[A]
  ): A =
    val deadline        = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test(
    "a cart filled and checked out is published as its node, its checkout and the edge between"
  ) {
    val id = "graph-1"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Pen", 1))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Ink", 2))
    val _  = cart(id).call(ShoppingCartEntity.removeItem).invoke("p2")
    val _  = cart(id).call(ShoppingCartEntity.checkout).invoke()

    val deltas = eventually("six deltas")(Some(published(id)).filter(_.sizeIs >= 6))
    assertEquals(
      deltas.map(d => d.key -> d.version),
      Vector(
        s"node:cart:$id"        -> 1L,
        s"node:cart:$id"        -> 2L,
        s"node:cart:$id"        -> 3L,
        s"node:cart:$id"        -> 4L,
        s"node:checkout:$id"    -> 4L,
        s"edge:checked-out:$id" -> 4L
      )
    )
    // The cart's node is its whole state each time; only the checkout turns `checkedOut`.
    assertEquals(
      deltas.take(4).map(_.properties),
      Vector(false, false, false, true).map(done => Map("cartId" -> id, "checkedOut" -> done))
    )
    assertEquals(deltas(4).labels, Vector("Checkout"))
    assertEquals(
      (deltas(5).edgeType, deltas(5).from, deltas(5).to),
      (Some("CHECKED_OUT"), Some(s"cart:$id"), Some(s"checkout:$id"))
    )
  }

  test("every record is a delta in its own right: keyed by element, about the cart, typed") {
    val id = "graph-2"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Pen", 1))
    val records = eventually("the record") {
      Some(publisher.publishedTo("cart-graph").filter(_.metadata.subject.contains(id)))
        .filter(_.nonEmpty)
    }
    val record = records.head
    assertEquals(record.key, Some(s"node:cart:$id"), "the record names its key")
    assertEquals(record.recordKey, Some(s"node:cart:$id"))
    assertEquals(record.metadata.subject, Some(id), "and is still about the cart")
    assertEquals(record.metadata.eventType, Some("ankka.graph-delta.v1"))
  }

  test(
    "a discarded cart is tombstoned above everything before it, and comes back above its tombstone"
  ) {
    val id        = "graph-3"
    val _         = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Pen", 1))
    val _         = cart(id).call(ShoppingCartEntity.discard).invoke()
    val tombstone = eventually("the tombstone")(published(id).find(_.isTombstone))
    assertEquals(tombstone.key, s"node:cart:$id")
    assert(
      published(id).filterNot(_.isTombstone).forall(_.version < tombstone.version),
      published(id).map(d => d.kind -> d.version).toString
    )

    // The same id starts again: its node is published above the tombstone, so it is live again.
    val _ = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p9", "Cap", 1))
    val again = eventually("the node after the tombstone") {
      published(id).find(d => !d.isTombstone && d.version > tombstone.version)
    }
    assertEquals(again.key, s"node:cart:$id")
    val versions = published(id).map(_.version)
    assertEquals(versions, versions.sorted, "the versions of one cart's elements only rise")
  }
