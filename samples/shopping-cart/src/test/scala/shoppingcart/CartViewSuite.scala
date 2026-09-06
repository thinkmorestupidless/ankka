package shoppingcart

import nakka.core.EntityId
import nakka.runtime.SqlSyntax.{jsonText, sql}
import nakka.runtime.{InMemoryPublisher, ProjectionRuntime}
import nakka.testkit.NakkaTestKit
import shoppingcart.application.{CartRows, CheckoutNotifier, ShoppingCartEntity}
import shoppingcart.domain.LineItem

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * Views and consumers against real projections.
 *
 * Projections are asynchronous, so every assertion here polls to a deadline rather than
 * reading once. That is not test hygiene papering over a race — it is the actual
 * consistency model, and a test that pretended otherwise would be testing a system
 * nakka does not provide.
 */
class CartViewSuite extends munit.FunSuite:

  override val munitTimeout = 4.minutes

  private var testKit: NakkaTestKit    = null
  private val publisher                = InMemoryPublisher()

  override def beforeAll(): Unit =
    testKit = NakkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CartRows.descriptor, CheckoutNotifier.descriptor),
      Seq(ProjectionRuntime.withPublisher(publisher))
    )

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def cart(id: String) =
    testKit.componentClient.forEventSourcedEntity(EntityId(id))

  private def rows = testKit.service.viewClient.forView(CartRows)

  /** Polls until `check` returns a value or the deadline passes. */
  private def eventually[A](
      description: String,
      within: FiniteDuration = 30.seconds
  )(check: => Option[A]): A =
    val deadline = System.nanoTime() + within.toNanos
    var last: Option[A] = None
    while last.isEmpty && System.nanoTime() < deadline do
      last = check
      if last.isEmpty then Thread.sleep(100)
    last.getOrElse(fail(s"$description did not happen within $within"))

  test("events reach the view as a queryable row") {
    val id = "view-basic"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 5))

    val row = eventually("the cart row appears with both products") {
      rows.get(id).filter(_.quantities.size == 2)
    }
    assertEquals(row.cartId, id)
    assertEquals(row.totalQuantity, 7)
    assertEquals(row.productIds, List("p1", "p2"))
    assert(!row.checkedOut)
  }

  test("the view folds repeated adds the way the entity does") {
    val id = "view-fold"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 2))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 3))

    val row = eventually("quantities are folded") {
      rows.get(id).filter(_.totalQuantity == 5)
    }
    assertEquals(row.quantities, Map("p1" -> 5))

    // The projection must agree with the entity, which is the whole point of a view.
    assertEquals(row.totalQuantity, cart(id).call(ShoppingCartEntity.totalQuantity).invoke())
  }

  test("a removal is projected too") {
    val id = "view-remove"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p2", "Gadget", 1))
    val _  = eventually("both products land")(rows.get(id).filter(_.quantities.size == 2))

    val _ = cart(id).call(ShoppingCartEntity.removeItem).invoke("p1")
    val row = eventually("the removal lands")(rows.get(id).filter(!_.quantities.contains("p1")))
    assertEquals(row.productIds, List("p2"))
  }

  test("rows can be queried by a field inside the payload") {
    val _ = cart("view-q-1").call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _ = cart("view-q-2").call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _ = eventually("both rows exist") {
      Option.when(rows.get("view-q-1").isDefined && rows.get("view-q-2").isDefined)(())
    }

    // Query by an attribute rather than by key — the reason views exist.
    val found = rows.where(jsonText("cartId") ++ sql" = ${"view-q-1"}")
    assertEquals(found.map(_.cartId), Vector("view-q-1"))
  }

  test("a checked-out cart survives in the view as a tombstone") {
    val id = "view-tombstone"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 4))
    val _  = eventually("the row exists")(rows.get(id))

    val _ = cart(id).call(ShoppingCartEntity.checkout).invoke()

    // The entity is gone...
    assert(cart(id).call(ShoppingCartEntity.getCart).invoke().isEmpty)

    // ...but onDelete kept the row, so order history still works.
    val row = eventually("the row is marked checked out")(rows.get(id).filter(_.checkedOut))
    assertEquals(row.totalQuantity, 4)
  }

  test("count and all see every projected row") {
    val _ = eventually("at least the rows written by earlier tests exist") {
      Option.when(rows.count() > 0L)(())
    }
    assert(rows.count() >= 1L)
    assert(rows.all().nonEmpty)
    assert(rows.count(jsonText("cartId") ++ sql" = ${"nope-does-not-exist"}") == 0L)
  }

  test("a consumer publishes only the events it selected") {
    val id = "consume-checkout"
    val _  = cart(id).call(ShoppingCartEntity.addItem).invoke(LineItem("p1", "Widget", 1))
    val _  = cart(id).call(ShoppingCartEntity.checkout).invoke()

    val published = eventually("the checkout notice is published") {
      Option(publisher.publishedTo("cart-checkouts").filter(_.text.contains(id)))
        .filter(_.nonEmpty)
    }
    assertEquals(published.size, 1)
    assert(published.head.text.contains(s"\"cartId\":\"$id\""), published.head.text)

    // ce-subject carries the entity id, so per-cart ordering survives on a broker.
    assertEquals(published.head.metadata.subject, Some(id))

    // ItemAdded was ignored, so nothing about it was published.
    assert(
      !publisher.publishedTo("cart-checkouts").exists(_.text.contains("Widget")),
      "ignored events must not be published"
    )
  }
